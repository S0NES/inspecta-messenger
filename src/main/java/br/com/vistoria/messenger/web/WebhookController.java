package br.com.vistoria.messenger.web;

import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.core.modules.financeiro.PagamentoService;
import br.com.vistoria.core.modules.notify.NotifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Borda pública única de webhook de terceiros (arquitetura.md §Auth). Sem Spring Security no
 * classpath: a verificação de assinatura fail-closed em cada handler é a única barreira —
 * assinatura ausente ou inválida devolve 401 antes de qualquer processamento.
 *
 * <p>Os handlers de assinatura/pagamento delegam a serviços {@code @Transactional} do inspecta-core
 * que persistem o webhook bruto (dedup) e publicam o evento de domínio via outbox na mesma
 * transação; o relay do inspecta-api drena a base compartilhada. O consumo no inspecta-worker
 * ({@code jobs.signature.webhook-received} / {@code jobs.payment.webhook-received}) é inalterado.</p>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final NotifyService notifyService;
    private final WebhookSignatureVerifier signatureVerifier;
    private final AutentiqueSignatureVerifier autentiqueVerifier;
    private final AbacatePaySignatureVerifier abacatePayVerifier;
    private final AssinaturaService assinaturaService;
    private final PagamentoService pagamentoService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Webhooks de broker de mensageria (resend / infobip / expo). */
    @PostMapping("/v1/webhooks/{broker}")
    public ResponseEntity<Void> webhook(@PathVariable String broker,
                                        @RequestBody String payload,
                                        @RequestHeader Map<String, String> headers) {
        // Verify webhook signature; fail closed on invalid/missing signature
        if (!signatureVerifier.verifySignature(broker, payload, headers)) {
            log.warn("[notify] webhook {} rejected: invalid or missing signature", broker);
            return ResponseEntity.status(401).build();
        }

        try {
            JsonNode body = mapper.readTree(payload);
            String eventId = firstText(body, "id", "event_id", "messageId");
            if (eventId == null) return ResponseEntity.ok().build();
            if (!notifyService.registrarWebhook(broker, eventId)) return ResponseEntity.ok().build();

            String externalId = firstText(body, "externalId", "messageId", "id");
            if (externalId == null) externalId = body.path("data").path("email_id").asText(null);
            String status = firstText(body, "status", "type");
            if (externalId != null && status != null) notifyService.atualizarStatusPorExternalId(externalId, status);
        } catch (Exception e) {
            log.warn("[notify] webhook {} invalido: {}", broker, e.toString());
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Webhook de assinatura (Autentique). Valida o HMAC-SHA256 do corpo cru
     * ({@code x-autentique-signature}, fail-closed) ANTES de qualquer parse, e delega ao
     * {@link AssinaturaService#receberWebhook(String, Map)}, que dedup por {@code event.id} e
     * publica {@code jobs.signature.webhook-received} via outbox.
     */
    @PostMapping("/v1/webhooks/assinatura/autentique")
    public ResponseEntity<Map<String, String>> webhookAutentique(
            @RequestBody String payload,
            @RequestHeader Map<String, String> headers) {
        if (!autentiqueVerifier.verify(payload, headers.get(AutentiqueSignatureVerifier.SIGNATURE_HEADER))) {
            log.warn("[autentique] webhook rejeitado: assinatura HMAC ausente ou inválida");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "unauthorized"));
        }
        // Sem try/catch aqui de propósito: receberWebhook (inspecta-core, @Transactional) já engole
        // exceção internamente e retorna — 2xx imediato antes de qualquer processamento pesado
        // (a lógica de negócio roda assíncrona no inspecta-worker).
        assinaturaService.receberWebhook(payload, headers);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Webhook de notificação de pagamento. Para o provider {@code abacatepay} valida o segredo do
     * webhook (query param {@code webhookSecret}, fail-closed). Delega ao
     * {@link PagamentoService#receberWebhook(String, String, String, Map)}, que publica
     * {@code jobs.payment.webhook-received} via outbox — os headers seguem até o inspecta-worker para a
     * segunda camada de verificação HMAC.
     */
    @PostMapping("/v1/webhooks/pagamento/{provider}")
    public ResponseEntity<Map<String, String>> webhookPagamento(
            @PathVariable String provider,
            @RequestBody String payload,
            @RequestParam(value = "webhookSecret", required = false) String webhookSecret,
            @RequestHeader Map<String, String> headers) {
        if ("abacatepay".equalsIgnoreCase(provider) && !abacatePayVerifier.verify(webhookSecret)) {
            log.warn("[pagamento] webhook abacatepay rejeitado: webhookSecret ausente ou inválido");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "unauthorized"));
        }
        // idem webhookAutentique: receberWebhook engole exceção e retorna; sempre respondemos 200.
        pagamentoService.receberWebhook(provider, extrairEventId(payload), payload, headers);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private String extrairEventId(String payload) {
        try {
            JsonNode json = mapper.readTree(payload);
            for (String campo : new String[]{"eventId", "event_id", "id"}) {
                String v = json.path(campo).asText(null);
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception e) {
            log.warn("[pagamento] webhook com payload nao-JSON");
        }
        return null;
    }

    private String firstText(JsonNode node, String... campos) {
        for (String c : campos) {
            String v = node.path(c).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}

package br.com.vistoria.messenger.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifica a autenticidade dos webhooks da AbacatePay recebidos em
 * {@code POST /v1/webhooks/pagamento/abacatepay}. Fail closed: rejeita webhook
 * sem segredo configurado ou com segredo divergente.
 *
 * <p>Esquema atual (docs.abacatepay.com/pages/webhooks): a AbacatePay entrega o
 * segredo do webhook como query param {@code ?webhookSecret=<secret>} na URL
 * configurada no painel. A comparacao e feita em tempo constante via
 * {@link MessageDigest#isEqual}.</p>
 *
 * <p>Esta e a UNICA camada de autenticidade do webhook AbacatePay no sistema —
 * o consumidor ({@code inspecta-worker}, {@code AbacatePayGateway.parseWebhook}) so
 * faz parsing do payload ja autenticado aqui, sem revalidar assinatura. A
 * AbacatePay tambem oferece HMAC via {@code X-Webhook-Signature} (chave publica
 * da conta), mas validar essa segunda camada de novo no consumidor seria
 * redundante com esta barreira na borda.</p>
 */
@Component
@Slf4j
public class AbacatePaySignatureVerifier {

    @Value("${inspecta.webhooks.abacatepay-webhook-secret:}")
    private String secret;

    /**
     * @param webhookSecret valor do query param {@code webhookSecret} (pode ser {@code null})
     * @return {@code true} apenas se o segredo estiver configurado e conferir com o
     *         recebido; {@code false} caso contrario (fail closed)
     */
    public boolean verify(String webhookSecret) {
        if (secret == null || secret.isBlank()) {
            log.warn("[abacatepay] webhook rejeitado: ABACATEPAY_WEBHOOK_SECRET nao configurado");
            return false;
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                webhookSecret.getBytes(StandardCharsets.UTF_8));
    }
}

package br.com.vistoria.messenger.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifica a autenticidade dos webhooks da Autentique recebidos em
 * {@code POST /v1/webhooks/assinatura/autentique}.
 *
 * <p>Esquema (docs.autentique.com.br/api/integration-basics/webhooks): a Autentique assina o
 * corpo cru com HMAC-SHA256 usando o segredo do webhook e envia o hex no header
 * {@code x-autentique-signature}. Validamos ANTES de qualquer parse do JSON. Fail closed:
 * segredo não configurado, header ausente ou assinatura divergente → rejeitado (401).</p>
 *
 * <p>Esta é a ÚNICA camada de autenticidade do webhook Autentique — o consumidor
 * ({@code inspecta-worker}, {@code AutentiqueBroker.parseWebhook}) só faz parsing do payload já
 * autenticado aqui.</p>
 */
@Component
@Slf4j
public class AutentiqueSignatureVerifier {

    /** Nome do header (Spring normaliza para minúsculas em {@code @RequestHeader Map}). */
    public static final String SIGNATURE_HEADER = "x-autentique-signature";

    @Value("${inspecta.webhooks.autentique-webhook-secret:}")
    private String secret;

    /**
     * @param rawBody         corpo cru da requisição (exatamente como recebido)
     * @param signatureHeader valor de {@code x-autentique-signature} (pode ser {@code null})
     * @return {@code true} só se o segredo estiver configurado e o HMAC-SHA256 hex do corpo
     *         bater com o header (comparação em tempo constante); {@code false} caso contrário
     */
    public boolean verify(String rawBody, String signatureHeader) {
        if (secret == null || secret.isBlank()) {
            log.warn("[autentique] webhook rejeitado: AUTENTIQUE_WEBHOOK_SECRET nao configurado");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank() || rawBody == null) {
            return false;
        }
        String calculado = hmacSha256Hex(rawBody, secret);
        return MessageDigest.isEqual(
                calculado.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("autentique: falha ao calcular HMAC-SHA256", e);
        }
    }
}

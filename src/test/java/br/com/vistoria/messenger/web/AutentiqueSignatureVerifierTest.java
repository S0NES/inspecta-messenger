package br.com.vistoria.messenger.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AutentiqueSignatureVerifier")
class AutentiqueSignatureVerifierTest {

    private AutentiqueSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new AutentiqueSignatureVerifier();
    }

    private static String hmac(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    @DisplayName("aceita quando o HMAC-SHA256 do corpo bate com o header")
    void verify_validSignature() throws Exception {
        ReflectionTestUtils.setField(verifier, "secret", "s3cr3t");
        String body = "{\"event\":{\"id\":\"evt-1\"}}";

        assertThat(verifier.verify(body, hmac(body, "s3cr3t"))).isTrue();
    }

    @Test
    @DisplayName("rejeita assinatura divergente")
    void verify_wrongSignature() {
        ReflectionTestUtils.setField(verifier, "secret", "s3cr3t");
        assertThat(verifier.verify("{\"a\":1}", "0000")).isFalse();
    }

    @Test
    @DisplayName("fail-closed: segredo não configurado")
    void verify_noSecret() {
        ReflectionTestUtils.setField(verifier, "secret", "");
        assertThat(verifier.verify("{}", "whatever")).isFalse();
    }

    @Test
    @DisplayName("fail-closed: header ausente")
    void verify_noHeader() {
        ReflectionTestUtils.setField(verifier, "secret", "s3cr3t");
        assertThat(verifier.verify("{}", null)).isFalse();
    }

    @Test
    @DisplayName("corpo diferente invalida a assinatura (comparação sobre o corpo cru)")
    void verify_bodyTampered() throws Exception {
        ReflectionTestUtils.setField(verifier, "secret", "s3cr3t");
        String sig = hmac("{\"amount\":10}", "s3cr3t");

        assertThat(verifier.verify("{\"amount\":9999}", sig)).isFalse();
    }
}

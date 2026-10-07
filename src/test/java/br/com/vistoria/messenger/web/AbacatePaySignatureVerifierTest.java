package br.com.vistoria.messenger.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AbacatePaySignatureVerifier - fail-closed webhookSecret (query param, docs.abacatepay.com)")
class AbacatePaySignatureVerifierTest {

    private AbacatePaySignatureVerifier withSecret(String secret) {
        var v = new AbacatePaySignatureVerifier();
        ReflectionTestUtils.setField(v, "secret", secret);
        return v;
    }

    @Test
    @DisplayName("aceita segredo igual ao configurado")
    void acceptsMatchingSecret() {
        assertThat(withSecret("s3cr3t").verify("s3cr3t")).isTrue();
    }

    @Test
    @DisplayName("rejeita segredo divergente")
    void rejectsMismatch() {
        assertThat(withSecret("s3cr3t").verify("nope")).isFalse();
    }

    @Test
    @DisplayName("rejeita quando o segredo nao esta configurado (fail closed)")
    void rejectsWhenSecretBlank() {
        assertThat(withSecret("").verify("s3cr3t")).isFalse();
        assertThat(withSecret(null).verify("s3cr3t")).isFalse();
    }

    @Test
    @DisplayName("rejeita query param ausente ou vazio")
    void rejectsMissingParam() {
        assertThat(withSecret("s3cr3t").verify(null)).isFalse();
        assertThat(withSecret("s3cr3t").verify("")).isFalse();
    }
}

package br.com.vistoria.messenger.signature.broker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MockSignatureBroker (inspecta-messenger)")
class MockSignatureBrokerTest {

    private final MockSignatureBroker broker = new MockSignatureBroker();

    @BeforeEach
    void ping() {
        assertThat(broker.nome()).isEqualTo("mock");
    }

    @Test
    @DisplayName("criarDocumento devolve 1 SignerResult com signing_url por signer")
    void criarDocumento() {
        UUID sid = UUID.randomUUID();
        SignatureBroker.EnvioResult r = broker.criarDocumento("Laudo",
                List.of(new SignatureBroker.SignerRef(sid, "Ana")), "%PDF".getBytes());

        assertThat(r.externalDocumentId()).startsWith("mock-doc-");
        assertThat(r.assinantes()).singleElement().satisfies(a -> {
            assertThat(a.signatarioId()).isEqualTo(sid);
            assertThat(a.externalSignerId()).startsWith("mock-signer-");
            assertThat(a.signingUrl()).isNotBlank();
        });
    }

    @Test
    @DisplayName("parseWebhook: shape real + public_id sem hífen")
    void parseWebhook() {
        SignatureBroker.WebhookEvent e = broker.parseWebhook(
                "{\"event\":{\"id\":\"evt-1\",\"type\":\"signature.accepted\","
              + "\"data\":{\"object\":\"signature\",\"document\":\"doc-1\",\"public_id\":\"834ab463-ab83-11f1-818d-42010a2b6021\"}}}");
        assertThat(e.externalDocumentId()).isEqualTo("doc-1");
        assertThat(e.externalSignerId()).isEqualTo("834ab463ab8311f1818d42010a2b6021");
    }

    @Test
    void parseWebhook_invalido() {
        assertThatThrownBy(() -> broker.parseWebhook("{\"event\":{}}")).isInstanceOf(IllegalArgumentException.class);
    }
}

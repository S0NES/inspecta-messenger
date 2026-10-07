package br.com.vistoria.messenger.signature.broker;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutentiqueBroker (inspecta-messenger)")
class AutentiqueBrokerTest {

    @Mock private HttpGateway httpGateway;

    private AutentiqueBroker broker;

    @BeforeEach
    void setUp() {
        broker = new AutentiqueBroker(httpGateway);
        ReflectionTestUtils.setField(broker, "apiUrl", "https://api.autentique.com.br/v2/graphql");
        ReflectionTestUtils.setField(broker, "apiKey", "tok-test");
        ReflectionTestUtils.setField(broker, "sandbox", false);
    }

    private SignatureBroker.SignerRef ref(String nome) {
        return new SignatureBroker.SignerRef(UUID.randomUUID(), nome);
    }

    @Test
    void nome() {
        assertThat(broker.nome()).isEqualTo("autentique");
    }

    @Test
    @DisplayName("criarDocumento: multipart name-only, casa por nome, dono da conta ignorado, public_id sem hífen")
    void criarDocumento_happyPath() throws Exception {
        var maria = ref("Maria da Silva");
        var joao = ref("Joao Pereira");

        String resp = "{\"data\":{\"createDocument\":{\"id\":\"doc-1\",\"signatures\":["
                + "{\"public_id\":\"owner\",\"name\":null,\"action\":null,\"link\":null},"
                + "{\"public_id\":\"833ff9e0-ab83-11f1-818d-42010a2b6021\",\"name\":\"Maria da Silva\",\"link\":{\"short_link\":\"https://assina.ae/m\"}},"
                + "{\"public_id\":\"834ab463-ab83-11f1-818d-42010a2b6021\",\"name\":\"Joao Pereira\",\"link\":{\"short_link\":\"https://assina.ae/j\"}}]}}}";
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, Map.of(), resp));

        SignatureBroker.EnvioResult r = broker.criarDocumento("Laudo x", List.of(maria, joao), "%PDF".getBytes());

        assertThat(r.externalDocumentId()).isEqualTo("doc-1");
        assertThat(r.assinantes()).extracting(SignatureBroker.SignerResult::signatarioId)
                .containsExactly(maria.signatarioId(), joao.signatarioId());
        assertThat(r.assinantes()).extracting(SignatureBroker.SignerResult::externalSignerId)
                .containsExactly("833ff9e0ab8311f1818d42010a2b6021", "834ab463ab8311f1818d42010a2b6021");
        assertThat(r.assinantes()).extracting(SignatureBroker.SignerResult::signingUrl)
                .containsExactly("https://assina.ae/m", "https://assina.ae/j");

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());
        HttpGatewayRequest req = captor.getValue();
        assertThat(req.headers().get("Authorization")).isEqualTo("Bearer tok-test");
        assertThat(req.headers().get("Content-Type")).startsWith("multipart/form-data; boundary=");
        assertThat(req.body()).contains("createDocument").contains("\"0\"").doesNotContain("sandbox: true").doesNotContain("\"email\"");
    }

    @Test
    @DisplayName("criarDocumento sandbox=true injeta 'sandbox: true'")
    void criarDocumento_sandbox() throws Exception {
        ReflectionTestUtils.setField(broker, "sandbox", true);
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, Map.of(),
                "{\"data\":{\"createDocument\":{\"id\":\"d\",\"signatures\":[{\"public_id\":\"s\",\"name\":\"Ana\",\"link\":{\"short_link\":\"u\"}}]}}}"));

        broker.criarDocumento("Laudo", List.of(ref("Ana")), "%PDF".getBytes());

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().body()).contains("sandbox: true");
    }

    @Test
    @DisplayName("criarDocumento propaga erro GraphQL")
    void criarDocumento_erroGraphql() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, Map.of(),
                "{\"errors\":[{\"message\":\"unavailable_credits\"}]}"));

        assertThatThrownBy(() -> broker.criarDocumento("L", List.of(ref("Ana")), "%PDF".getBytes()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("parseWebhook: shape real (event.data direto) + public_id normalizado")
    void parseWebhook_real() {
        String payload = "{\"event\":{\"id\":\"b0c54b50-082b-4722-92a5-e6660e7dfb3d\",\"type\":\"signature.accepted\","
                + "\"data\":{\"object\":\"signature\",\"public_id\":\"834ab463-ab83-11f1-818d-42010a2b6021\","
                + "\"document\":\"d35e6c000c0403bc2f1fc72d895c9287f28d7efbc9ae1e525\"}}}";
        SignatureBroker.WebhookEvent e = broker.parseWebhook(payload);
        assertThat(e.tipo()).isEqualTo("signature.accepted");
        assertThat(e.externalDocumentId()).isEqualTo("d35e6c000c0403bc2f1fc72d895c9287f28d7efbc9ae1e525");
        assertThat(e.externalSignerId()).isEqualTo("834ab463ab8311f1818d42010a2b6021");
    }

    @Test
    @DisplayName("parseWebhook: document.finished usa data.id")
    void parseWebhook_document() {
        SignatureBroker.WebhookEvent e = broker.parseWebhook(
                "{\"event\":{\"id\":\"e\",\"type\":\"document.finished\",\"data\":{\"object\":\"document\",\"id\":\"doc-9\"}}}");
        assertThat(e.externalDocumentId()).isEqualTo("doc-9");
        assertThat(e.externalSignerId()).isNull();
    }

    @Test
    void parseWebhook_invalido() {
        assertThatThrownBy(() -> broker.parseWebhook("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}

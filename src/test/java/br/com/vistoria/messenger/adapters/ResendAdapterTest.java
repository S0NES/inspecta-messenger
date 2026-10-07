package br.com.vistoria.messenger.adapters;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResendAdapter}: verifies that the adapter correctly
 * formats email payloads and invokes HttpGateway with the correct HTTP method,
 * URI, headers (including Authorization), and JSON body matching Resend API.
 * Tests both success and error scenarios.
 */
class ResendAdapterTest {

    private HttpGateway httpGateway;
    private ResendAdapter adapter;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        httpGateway = mock(HttpGateway.class);
        adapter = new ResendAdapter(httpGateway);
        // Simulate configured API key via reflection (since it's @Value injected)
        try {
            var field = adapter.getClass().getDeclaredField("apiKey");
            field.setAccessible(true);
            field.set(adapter, "test-api-key-123");
            var fromField = adapter.getClass().getDeclaredField("from");
            fromField.setAccessible(true);
            fromField.set(adapter, "noreply@inspecta.com");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void sendInvokesHttpGatewayWithCorrectPostMethodAndResendUri() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"id\": \"ext-resend-123\"}"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Welcome", "Hello {{name}}", Map.of("name", "Alice"));
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        HttpGatewayRequest request = captor.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("https://api.resend.com/emails");
    }

    @Test
    void sendIncludesAuthorizationHeaderWithBearerToken() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"id\": \"ext-resend-456\"}"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        HttpGatewayRequest request = captor.getValue();
        assertThat(request.headers()).containsEntry("Authorization", "Bearer test-api-key-123");
        assertThat(request.headers()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void sendFormatsBodAsJsonWithCorrectResendFields() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"id\": \"ext-resend-789\"}"));

        var rendered = new BrokerAdapter.Rendered("email", "recipient@example.com", "Test Subject", "Test Body", Map.of());
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        HttpGatewayRequest request = captor.getValue();
        var payload = mapper.readTree(request.body());
        assertThat(payload.get("from").asText()).isEqualTo("noreply@inspecta.com");
        assertThat(payload.get("to").asText()).isEqualTo("recipient@example.com");
        assertThat(payload.get("subject").asText()).isEqualTo("Test Subject");
        assertThat(payload.get("html").asText()).isEqualTo("Test Body");
    }

    @Test
    void sendHandlesNullSubjectByUsingEmptyString() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"id\": \"ext-123\"}"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", null, "Body", Map.of());
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        var payload = mapper.readTree(captor.getValue().body());
        assertThat(payload.get("subject").asText()).isEmpty();
    }

    @Test
    void sendReturnsExternalIdFromResendResponse() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"id\": \"ext-resend-external-id-xyz\"}"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).isEqualTo("ext-resend-external-id-xyz");
    }

    @Test
    void sendThrowsExceptionWhenHttpGatewayReturnsNonSuccess() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(400, "Invalid email"));

        var rendered = new BrokerAdapter.Rendered("email", "invalid@", "Subject", "Body", Map.of());

        assertThatThrownBy(() -> adapter.send(rendered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resend")
                .hasMessageContaining("400");
    }

    @Test
    void sendThrowsExceptionOn500Error() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(500, "Internal Server Error"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());

        assertThatThrownBy(() -> adapter.send(rendered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resend")
                .hasMessageContaining("500");
    }

    @Test
    void sendReturnsMockIdWhenResponseHasMissingIdField() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"status\": \"sent\"}"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendReturnsMockIdWhenResponseBodyIsEmpty() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, ""));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendReturnsMockIdWhenResponseBodyIsNull() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, (String) null));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendReturnsMockIdWhenResponseIdFieldIsExplicitNull() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"id\": null}"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendReturnsMockIdWhenResponseBodyIsInvalidJson() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "not valid json {{{"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendIncludesIdempotencyKeyHeaderWhenRenderedCarriesOne() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"id\": \"ext-1\"}"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body",
                Map.of(), "evt-abc-123");
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().headers()).containsEntry("Idempotency-Key", "evt-abc-123");
    }

    @Test
    void sendOmitsIdempotencyKeyHeaderWhenRenderedHasNone() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"id\": \"ext-1\"}"));

        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().headers()).doesNotContainKey("Idempotency-Key");
    }

    @Test
    void configuradoReturnsTrueWhenApiKeyIsConfigured() {
        assertThat(adapter.configurado()).isTrue();
    }

    @Test
    void configuradoReturnsFalseWhenApiKeyIsBlank() throws Exception {
        var field = adapter.getClass().getDeclaredField("apiKey");
        field.setAccessible(true);
        field.set(adapter, "");

        assertThat(adapter.configurado()).isFalse();
    }

    @Test
    void configuradoReturnsFalseWhenApiKeyIsNull() throws Exception {
        var field = adapter.getClass().getDeclaredField("apiKey");
        field.setAccessible(true);
        field.set(adapter, null);

        assertThat(adapter.configurado()).isFalse();
    }

    @Test
    void nameReturnsCorrectBrokerName() {
        assertThat(adapter.name()).isEqualTo("resend");
    }
}

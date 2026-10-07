package br.com.vistoria.messenger.adapters;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExpoAdapter}: verifies that the adapter correctly
 * formats push notification payloads and invokes HttpGateway with the correct
 * HTTP method, URI, headers (including Authorization when configured), and
 * JSON body array matching Expo Push API. Tests success, error, and ticket error scenarios.
 */
class ExpoAdapterTest {

    private HttpGateway httpGateway;
    private ExpoAdapter adapter;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        httpGateway = mock(HttpGateway.class);
        adapter = new ExpoAdapter(httpGateway);
        // Simulate configured access token via reflection
        try {
            var field = adapter.getClass().getDeclaredField("accessToken");
            field.setAccessible(true);
            field.set(adapter, "ExponentPushToken[test-token-123]");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void sendInvokesHttpGatewayWithCorrectPostMethodAndExpoUri() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"data\": [{\"id\": \"ticket-123\"}]}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user-token]", "Title", "Body message", Map.of());
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        HttpGatewayRequest request = captor.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("https://exp.host/--/api/v2/push/send");
    }

    @Test
    void sendIncludesAuthorizationHeaderWhenAccessTokenConfigured() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"data\": [{\"id\": \"ticket-456\"}]}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user-token]", "Title", "Body", Map.of());
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        HttpGatewayRequest request = captor.getValue();
        assertThat(request.headers()).containsEntry("Authorization", "Bearer ExponentPushToken[test-token-123]");
        assertThat(request.headers()).containsEntry("Content-Type", "application/json");
        assertThat(request.headers()).containsEntry("Accept", "application/json");
    }

    @Test
    void sendFormatsBodyAsJsonArrayWithCorrectExpoFields() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"data\": [{\"id\": \"ticket-789\"}]}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[target]", "Alert Title", "Alert Body", Map.of());
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        HttpGatewayRequest request = captor.getValue();
        JsonNode array = mapper.readTree(request.body());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(1);

        JsonNode message = array.get(0);
        assertThat(message.get("to").asText()).isEqualTo("ExponentPushToken[target]");
        assertThat(message.get("title").asText()).isEqualTo("Alert Title");
        assertThat(message.get("body").asText()).isEqualTo("Alert Body");
    }

    @Test
    void sendHandlesNullTitleByUsingEmptyString() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"data\": [{\"id\": \"ticket-111\"}]}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[target]", null, "Body text", Map.of());
        adapter.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        JsonNode array = mapper.readTree(captor.getValue().body());
        assertThat(array.get(0).get("title").asText()).isEmpty();
    }

    @Test
    void sendReturnsTicketIdFromExpoResponse() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"data\": [{\"id\": \"ticket-expo-external-xyz\"}]}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Title", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).isEqualTo("ticket-expo-external-xyz");
    }

    @Test
    void sendThrowsExceptionWhenHttpGatewayReturnsNonSuccess() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(400, "Invalid token"));

        var rendered = new BrokerAdapter.Rendered("push", "InvalidToken", "Title", "Body", Map.of());

        assertThatThrownBy(() -> adapter.send(rendered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expo")
                .hasMessageContaining("400");
    }

    @Test
    void sendThrowsExceptionOn500Error() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(500, "Expo service unavailable"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Title", "Body", Map.of());

        assertThatThrownBy(() -> adapter.send(rendered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expo")
                .hasMessageContaining("500");
    }

    @Test
    void sendThrowsExceptionWhenTicketStatusIsError() throws Exception {
        String errorResponse = "{\"data\": [{\"status\": \"error\", \"message\": \"Device token is invalid\"}]}";
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, errorResponse));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[invalid]", "Title", "Body", Map.of());

        assertThatThrownBy(() -> adapter.send(rendered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expo ticket error")
                .hasMessageContaining("Device token is invalid");
    }

    @Test
    void sendReturnsMockIdWhenDataArrayIsMissing() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"status\": \"queued\"}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Title", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendReturnsMockIdWhenDataArrayIsEmpty() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"data\": []}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Title", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendReturnsMockIdWhenResponseBodyIsEmpty() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, ""));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Title", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendReturnsMockIdWhenResponseBodyIsNull() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, (String) null));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Title", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendReturnsMockIdWhenResponseBodyIsInvalidJson() throws Exception {
        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "invalid json }{{}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Title", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void configuradoReturnsTrueWhenAccessTokenIsConfigured() {
        assertThat(adapter.configurado()).isTrue();
    }

    @Test
    void configuradoReturnsFalseWhenAccessTokenIsBlank() throws Exception {
        var field = adapter.getClass().getDeclaredField("accessToken");
        field.setAccessible(true);
        field.set(adapter, "");

        assertThat(adapter.configurado()).isFalse();
    }

    @Test
    void configuradoReturnsFalseWhenAccessTokenIsNull() throws Exception {
        var field = adapter.getClass().getDeclaredField("accessToken");
        field.setAccessible(true);
        field.set(adapter, null);

        assertThat(adapter.configurado()).isFalse();
    }

    @Test
    void nameReturnsCorrectBrokerName() {
        assertThat(adapter.name()).isEqualTo("expo");
    }

    @Test
    void sendDoesNotIncludeAuthorizationHeaderWhenNotConfigured() throws Exception {
        // Setup adapter without access token
        var adapter2 = new ExpoAdapter(httpGateway);
        try {
            var field = adapter2.getClass().getDeclaredField("accessToken");
            field.setAccessible(true);
            field.set(adapter2, "");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(httpGateway.execute(any())).thenReturn(new HttpGatewayResponse(200, "{\"data\": [{\"id\": \"ticket-222\"}]}"));

        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Title", "Body", Map.of());
        adapter2.send(rendered);

        ArgumentCaptor<HttpGatewayRequest> captor = ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway).execute(captor.capture());

        HttpGatewayRequest request = captor.getValue();
        assertThat(request.headers()).doesNotContainKey("Authorization");
    }
}

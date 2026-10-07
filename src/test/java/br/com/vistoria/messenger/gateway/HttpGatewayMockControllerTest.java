package br.com.vistoria.messenger.gateway;

import br.com.vistoria.core.gateway.HttpGatewayResponse;
import br.com.vistoria.core.gateway.MockHttpGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link HttpGatewayMockController}: verifies that the mock
 * HTTP gateway controller correctly programs stub responses and resets the
 * mock gateway state. Tests both endpoints with various status codes and payloads.
 */
class HttpGatewayMockControllerTest {

    private MockHttpGateway mockHttpGateway;
    private HttpGatewayMockController controller;

    @BeforeEach
    void setUp() {
        mockHttpGateway = mock(MockHttpGateway.class);
        controller = new HttpGatewayMockController(mockHttpGateway);
    }

    @Test
    void stubNextAcceptsValidRequest() {
        var req = new HttpGatewayMockController.StubRequest(200, "{\"id\": \"test-123\"}", Map.of("Content-Type", "application/json"));
        ResponseEntity<Void> response = controller.stubNext(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void stubNextProgramsResponseWithCorrectStatus() {
        var req = new HttpGatewayMockController.StubRequest(201, "{\"status\": \"created\"}", Map.of());
        controller.stubNext(req);

        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway).programarProxima(captor.capture());

        HttpGatewayResponse captured = captor.getValue();
        assertThat(captured.status()).isEqualTo(201);
    }

    @Test
    void stubNextProgramsResponseWithCorrectBody() {
        String body = "{\"id\": \"ext-123\", \"status\": \"sent\"}";
        var req = new HttpGatewayMockController.StubRequest(200, body, Map.of());
        controller.stubNext(req);

        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway).programarProxima(captor.capture());

        assertThat(captor.getValue().body()).isEqualTo(body);
    }

    @Test
    void stubNextProgramsResponseWithHeaders() {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer token-123",
                "Content-Type", "application/json",
                "X-Custom-Header", "custom-value"
        );
        var req = new HttpGatewayMockController.StubRequest(200, "{}", headers);
        controller.stubNext(req);

        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway).programarProxima(captor.capture());

        HttpGatewayResponse captured = captor.getValue();
        assertThat(captured.headers())
                .containsEntry("Authorization", "Bearer token-123")
                .containsEntry("Content-Type", "application/json")
                .containsEntry("X-Custom-Header", "custom-value");
    }

    @Test
    void stubNextHandlesEmptyHeaders() {
        var req = new HttpGatewayMockController.StubRequest(200, "{}", Map.of());
        ResponseEntity<Void> response = controller.stubNext(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(mockHttpGateway).programarProxima(new HttpGatewayResponse(200, Map.of(), "{}"));
    }

    @Test
    void stubNextHandlesEmptyBody() {
        var req = new HttpGatewayMockController.StubRequest(204, "", Map.of());
        ResponseEntity<Void> response = controller.stubNext(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void stubNextHandles400BadRequest() {
        var req = new HttpGatewayMockController.StubRequest(400, "Invalid request", Map.of());
        ResponseEntity<Void> response = controller.stubNext(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway).programarProxima(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(400);
    }

    @Test
    void stubNextHandles500ServerError() {
        var req = new HttpGatewayMockController.StubRequest(500, "Internal Server Error", Map.of());
        ResponseEntity<Void> response = controller.stubNext(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway).programarProxima(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(500);
    }

    @Test
    void stubNextHandlesComplexJsonBody() {
        String complexBody = "{\"data\": [{\"id\": \"123\", \"status\": \"success\"}], \"meta\": {\"count\": 1}}";
        var req = new HttpGatewayMockController.StubRequest(200, complexBody, Map.of());
        controller.stubNext(req);

        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway).programarProxima(captor.capture());

        assertThat(captor.getValue().body()).isEqualTo(complexBody);
    }

    @Test
    void resetReturnsNoContent() {
        ResponseEntity<Void> response = controller.reset();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void resetCallsLimparOnMockGateway() {
        controller.reset();

        verify(mockHttpGateway).limpar();
    }

    @Test
    void resetClearsAllProgrammedResponses() {
        // First, program a response
        var req = new HttpGatewayMockController.StubRequest(200, "{}", Map.of());
        controller.stubNext(req);

        // Then reset
        controller.reset();

        verify(mockHttpGateway).limpar();
    }

    @Test
    void stubNextMultipleTimesStacksResponses() {
        var req1 = new HttpGatewayMockController.StubRequest(200, "{\"id\": \"1\"}", Map.of());
        var req2 = new HttpGatewayMockController.StubRequest(201, "{\"id\": \"2\"}", Map.of());

        controller.stubNext(req1);
        controller.stubNext(req2);

        // Verify that programarProxima was called twice with different arguments
        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway, org.mockito.Mockito.times(2)).programarProxima(captor.capture());

        // Verify that the calls had different status codes
        java.util.List<HttpGatewayResponse> capturedValues = captor.getAllValues();
        assertThat(capturedValues).hasSize(2);
        assertThat(capturedValues.get(0).status()).isEqualTo(200);
        assertThat(capturedValues.get(1).status()).isEqualTo(201);
    }

    @Test
    void stubNextHandlesNullHeaders() {
        // Edge case: if headers are null, the response should still be created
        var req = new HttpGatewayMockController.StubRequest(200, "{}", null);
        ResponseEntity<Void> response = controller.stubNext(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void stubNextProgramsResendErrorScenario() {
        Map<String, String> resendHeaders = Map.of(
                "content-type", "application/json",
                "x-resend-signature", "test-sig"
        );
        String errorBody = "{\"message\": \"Invalid email address\"}";
        var req = new HttpGatewayMockController.StubRequest(400, errorBody, resendHeaders);
        controller.stubNext(req);

        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway).programarProxima(captor.capture());

        HttpGatewayResponse captured = captor.getValue();
        assertThat(captured.status()).isEqualTo(400);
        assertThat(captured.body()).isEqualTo(errorBody);
        assertThat(captured.headers()).containsEntry("content-type", "application/json");
    }

    @Test
    void stubNextProgramsExpoSuccessScenario() {
        Map<String, String> headers = Map.of("content-type", "application/json");
        String successBody = "{\"data\": [{\"id\": \"ticket-123\", \"status\": null}]}";
        var req = new HttpGatewayMockController.StubRequest(200, successBody, headers);
        controller.stubNext(req);

        ArgumentCaptor<HttpGatewayResponse> captor = ArgumentCaptor.forClass(HttpGatewayResponse.class);
        verify(mockHttpGateway).programarProxima(captor.capture());

        HttpGatewayResponse captured = captor.getValue();
        assertThat(captured.status()).isEqualTo(200);
        assertThat(captured.body()).contains("ticket-123");
    }
}

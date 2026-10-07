package br.com.vistoria.messenger.adapters;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ExpoAdapter implements BrokerAdapter {

    private final HttpGateway httpGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${inspecta.notify.expo-access-token:}") private String accessToken;

    @Override public String name() { return "expo"; }

    public boolean configurado() { return accessToken != null && !accessToken.isBlank(); }

    @Override
    public String send(Rendered msg) throws Exception {
        // Expo nao expoe header de idempotencia; o residual de retry fica coberto pelo
        // unique-constraint entregas.idempotency_key + o guard porIdempotencyKey no Dispatcher.
        String body = mapper.writeValueAsString(List.of(Map.of(
                "to", msg.destinatario(),
                "title", msg.assunto() == null ? "" : msg.assunto(),
                "body", msg.corpo())));

        Map<String, String> headers = new HashMap<>(Map.of("Content-Type", "application/json", "Accept", "application/json"));
        if (configurado()) headers.put("Authorization", "Bearer " + accessToken);

        HttpGatewayResponse res = httpGateway.execute(HttpGatewayRequest.post(
                URI.create("https://exp.host/--/api/v2/push/send"), headers, body));

        if (!res.sucesso()) throw new IllegalStateException("expo " + res.status() + ": " + res.body());
        return extrairId(res.body());
    }

    private String extrairId(String body) {
        if (body == null || body.isBlank()) return "mock-" + UUID.randomUUID();
        JsonNode ticket = readSafely(body).path("data").path(0);
        if (ticket.isMissingNode()) return "mock-" + UUID.randomUUID();
        if ("error".equals(ticket.path("status").asText()))
            throw new IllegalStateException("expo ticket error: " + ticket.path("message").asText("unknown"));
        return ticket.path("id").asText();
    }

    private JsonNode readSafely(String body) {
        try { return mapper.readTree(body); } catch (Exception e) { return mapper.createObjectNode(); }
    }
}

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
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ResendAdapter implements BrokerAdapter {

    private final HttpGateway httpGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${inspecta.notify.resend-api-key:}") private String apiKey;
    @Value("${inspecta.notify.resend-from:Inspecta <noreply@example.com>}") private String from;

    @Override public String name() { return "resend"; }

    public boolean configurado() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public String send(Rendered msg) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "from", from,
                "to", msg.destinatario(),
                "subject", msg.assunto() == null ? "" : msg.assunto(),
                "html", msg.corpo()));

        Map<String, String> headers = new HashMap<>(Map.of(
                "Authorization", "Bearer " + apiKey,
                "Content-Type", "application/json"));
        // Idempotencia na origem: o Resend deduplica requests com a mesma Idempotency-Key por
        // 24h e devolve a resposta original em vez de reenviar. Cobre retry do loop do
        // Dispatcher (timeout apos o Resend ja ter aceitado), redelivery do RabbitMQ e replay
        // de DLQ — sem isso, cada tentativa e um e-mail real a mais.
        if (msg.idempotencyKey() != null && !msg.idempotencyKey().isBlank()) {
            headers.put("Idempotency-Key", msg.idempotencyKey());
        }

        HttpGatewayResponse res = httpGateway.execute(HttpGatewayRequest.post(
                URI.create("https://api.resend.com/emails"), headers, body));

        if (!res.sucesso()) throw new IllegalStateException("resend " + res.status() + ": " + res.body());
        return extrairId(res.body());
    }

    private String extrairId(String body) {
        if (body == null || body.isBlank()) return "mock-" + UUID.randomUUID();
        JsonNode id = readSafely(body).path("id");
        return id.isMissingNode() || id.isNull() ? "mock-" + UUID.randomUUID() : id.asText();
    }

    private JsonNode readSafely(String body) {
        try { return mapper.readTree(body); } catch (Exception e) { return mapper.createObjectNode(); }
    }
}

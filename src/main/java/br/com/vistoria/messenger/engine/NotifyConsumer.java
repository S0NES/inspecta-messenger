package br.com.vistoria.messenger.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotifyConsumer {

    private final Dispatcher dispatcher;
    private final ObjectMapper mapper = new ObjectMapper();

    @RabbitListener(queues = "q.notify")
    public void on(Message message) throws Exception {
        JsonNode e = mapper.readTree(message.getBody());

        // Handle double-JSON-encoding quirk from outbox relay:
        // if the parsed node is a text/string, parse it again
        if (e.isTextual()) {
            e = mapper.readTree(e.asText());
        }

        Map<String, Object> variaveis = new HashMap<>();
        e.path("variaveis").fields().forEachRemaining(f -> variaveis.put(f.getKey(), f.getValue().asText()));

        dispatcher.dispatch(new Dispatcher.Outbound(
                e.path("eventId").asText(),
                e.path("canal").asText("email"),
                e.path("templateId").asText(),
                e.path("destinatario").asText(),
                variaveis,
                e.hasNonNull("tenantId") ? UUID.fromString(e.path("tenantId").asText()) : null));
    }
}

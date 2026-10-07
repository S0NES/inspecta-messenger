package br.com.vistoria.messenger.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link NotifyConsumer}'s message-handling logic, exercised directly
 * (no live RabbitMQ broker) by constructing AMQP {@link Message} payloads and invoking
 * the listener method. Includes coverage of the known double-JSON-encoding quirk
 * produced by the outbox relay upstream (see class javadoc / task notes).
 */
class NotifyConsumerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Dispatcher dispatcher;
    private NotifyConsumer consumer;

    @BeforeEach
    void setUp() {
        dispatcher = mock(Dispatcher.class);
        consumer = new NotifyConsumer(dispatcher);
    }

    private Message messageFor(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private static final UUID TENANT_1 = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Test
    void onParsesNormalPayloadIntoOutbound() throws Exception {
        Map<String, Object> variaveis = new LinkedHashMap<>();
        variaveis.put("nome", "Ana");
        variaveis.put("idade", 30);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-1");
        payload.put("canal", "email");
        payload.put("templateId", "tpl-1");
        payload.put("destinatario", "a@b.com");
        payload.put("tenantId", TENANT_1.toString());
        payload.put("variaveis", variaveis);

        consumer.on(messageFor(mapper.writeValueAsString(payload)));

        ArgumentCaptor<Dispatcher.Outbound> captor = ArgumentCaptor.forClass(Dispatcher.Outbound.class);
        verify(dispatcher).dispatch(captor.capture());

        Dispatcher.Outbound outbound = captor.getValue();
        assertThat(outbound.idempotencyKey()).isEqualTo("evt-1");
        assertThat(outbound.canal()).isEqualTo("email");
        assertThat(outbound.templateId()).isEqualTo("tpl-1");
        assertThat(outbound.destinatario()).isEqualTo("a@b.com");
        assertThat(outbound.tenantId()).isEqualTo(TENANT_1);
        assertThat(outbound.variaveis()).containsEntry("nome", "Ana").containsEntry("idade", "30");
    }

    @Test
    void onUnwrapsDoubleEncodedOutboxPayload() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-2");
        payload.put("canal", "sms");
        payload.put("templateId", "tpl-2");
        payload.put("destinatario", "+5511999999999");
        payload.put("variaveis", Map.of("codigo", "1234"));

        String innerJson = mapper.writeValueAsString(payload);
        // Simulate the outbox relay's double-JSON-encoding quirk: the AMQP body is a
        // JSON string literal whose *content* is itself a JSON document.
        String doubleEncoded = mapper.writeValueAsString(innerJson);

        consumer.on(messageFor(doubleEncoded));

        ArgumentCaptor<Dispatcher.Outbound> captor = ArgumentCaptor.forClass(Dispatcher.Outbound.class);
        verify(dispatcher).dispatch(captor.capture());

        Dispatcher.Outbound outbound = captor.getValue();
        assertThat(outbound.idempotencyKey()).isEqualTo("evt-2");
        assertThat(outbound.canal()).isEqualTo("sms");
        assertThat(outbound.templateId()).isEqualTo("tpl-2");
        assertThat(outbound.destinatario()).isEqualTo("+5511999999999");
        assertThat(outbound.variaveis()).containsEntry("codigo", "1234");
    }

    @Test
    void onDefaultsCanalToEmailAndTenantIdToNullWhenAbsent() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-3");
        payload.put("templateId", "tpl-3");
        payload.put("destinatario", "c@d.com");

        consumer.on(messageFor(mapper.writeValueAsString(payload)));

        ArgumentCaptor<Dispatcher.Outbound> captor = ArgumentCaptor.forClass(Dispatcher.Outbound.class);
        verify(dispatcher).dispatch(captor.capture());

        Dispatcher.Outbound outbound = captor.getValue();
        assertThat(outbound.canal()).isEqualTo("email");
        assertThat(outbound.tenantId()).isNull();
        assertThat(outbound.variaveis()).isEmpty();
    }

    @Test
    void onTreatsNullTenantIdAsAbsent() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-4");
        payload.put("canal", "push");
        payload.put("templateId", "tpl-4");
        payload.put("destinatario", "token-abc");
        payload.put("tenantId", null);

        consumer.on(messageFor(mapper.writeValueAsString(payload)));

        ArgumentCaptor<Dispatcher.Outbound> captor = ArgumentCaptor.forClass(Dispatcher.Outbound.class);
        verify(dispatcher).dispatch(captor.capture());

        assertThat(captor.getValue().tenantId()).isNull();
    }

    @Test
    void onDoesNotSwallowDispatcherFailures() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt-5");
        payload.put("canal", "email");
        payload.put("templateId", "tpl-5");
        payload.put("destinatario", "e@f.com");

        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(dispatcher).dispatch(any());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> consumer.on(messageFor(mapper.writeValueAsString(payload))));
    }

    @Test
    void onPropagatesExceptionForSyntacticallyMalformedJson() {
        // Not a double-encoding quirk (that's a *valid* JSON string containing JSON) -
        // this is genuinely broken JSON on the wire. The listener must not swallow the
        // parse failure: it has to propagate so Spring's container-level retry/DLQ
        // routing (NotifyRabbitConfig: defaultRequeueRejected(false) -> q.notify.dlq)
        // actually engages instead of silently dropping the message.
        Message malformed = messageFor("{ this is not valid json ");

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> consumer.on(malformed));

        org.mockito.Mockito.verifyNoInteractions(dispatcher);
    }

    @Test
    void onPropagatesExceptionWhenDoubleEncodedInnerPayloadIsMalformed() {
        // The outer body is a valid JSON string (so the isTextual() unwrap fires), but
        // its *content* isn't valid JSON. Must still fail loud, not swallow silently.
        String doubleEncodedButBroken;
        try {
            doubleEncodedButBroken = mapper.writeValueAsString("{ broken inner json");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Message malformed = messageFor(doubleEncodedButBroken);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> consumer.on(malformed));

        org.mockito.Mockito.verifyNoInteractions(dispatcher);
    }
}

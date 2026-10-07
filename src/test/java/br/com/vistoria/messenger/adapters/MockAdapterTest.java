package br.com.vistoria.messenger.adapters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MockAdapter}: verifies that the mock adapter correctly
 * logs and returns mock external IDs for all channels, supporting fallback
 * when real brokers are not configured or for unsupported channels.
 */
class MockAdapterTest {

    private MockAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MockAdapter("email");
    }

    @Test
    void sendReturnsMockIdWithExpectedFormat() {
        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId)
                .startsWith("mock-")
                .hasSize("mock-".length() + 36); // UUID is 36 chars
    }

    @Test
    void sendReturnsDifferentMockIdOnEachCall() {
        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String id1 = adapter.send(rendered);
        String id2 = adapter.send(rendered);

        assertThat(id1)
                .startsWith("mock-")
                .isNotEqualTo(id2);
    }

    @Test
    void nameReturnsCorrectMockNameWithChannel() {
        MockAdapter emailAdapter = new MockAdapter("email");
        MockAdapter smsAdapter = new MockAdapter("sms");
        MockAdapter pushAdapter = new MockAdapter("push");
        MockAdapter unknownAdapter = new MockAdapter("whatsapp");

        assertThat(emailAdapter.name()).isEqualTo("mock-email");
        assertThat(smsAdapter.name()).isEqualTo("mock-sms");
        assertThat(pushAdapter.name()).isEqualTo("mock-push");
        assertThat(unknownAdapter.name()).isEqualTo("mock-whatsapp");
    }

    @Test
    void sendHandlesNullSubject() {
        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", null, "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendHandlesNullBody() {
        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", null, Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendHandlesNullDestinario() {
        var rendered = new BrokerAdapter.Rendered("email", null, "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendHandlesEmptyDestinario() {
        var rendered = new BrokerAdapter.Rendered("email", "", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendHandlesEmptyVareiasMap() {
        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendHandlesNullVareiasMap() {
        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", null);
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendHandlesSmsChannel() {
        MockAdapter smsAdapter = new MockAdapter("sms");
        var rendered = new BrokerAdapter.Rendered("sms", "+5511999999999", null, "Codigo: 1234", Map.of());
        String externalId = smsAdapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendHandlesPushChannel() {
        MockAdapter pushAdapter = new MockAdapter("push");
        var rendered = new BrokerAdapter.Rendered("push", "ExponentPushToken[user]", "Alert", "Notification", Map.of());
        String externalId = pushAdapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendHandlesComplexVarieias() {
        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject",
                "Body", Map.of("nome", "Maria", "idade", 30, "ativo", true));
        String externalId = adapter.send(rendered);

        assertThat(externalId).startsWith("mock-");
    }

    @Test
    void sendDoesNotThrowException() {
        var rendered = new BrokerAdapter.Rendered("email", "user@example.com", "Subject", "Body", Map.of());
        // Should not throw
        adapter.send(rendered);
    }
}

package br.com.vistoria.messenger.adapters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InfobipAdapter}: verifies that the stub adapter
 * correctly reports its configuration status and throws
 * UnsupportedOperationException when send() is invoked (per T4.2 spec).
 * When SMS integration becomes required, this adapter can be replaced with
 * a real Infobip API client implementation.
 */
class InfobipAdapterTest {

    private InfobipAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new InfobipAdapter();
    }

    @Test
    void sendThrowsUnsupportedOperationExceptionWithClearMessage() {
        var rendered = new BrokerAdapter.Rendered("sms", "+5511999999999", null, "SMS message", Map.of());

        assertThatThrownBy(() -> adapter.send(rendered))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Infobip SMS integration not implemented")
                .hasMessageContaining("stub");
    }

    @Test
    void configuradoReturnsTrueWhenApiKeyIsConfigured() throws Exception {
        var field = adapter.getClass().getDeclaredField("apiKey");
        field.setAccessible(true);
        field.set(adapter, "test-api-key-xyz");

        assertThat(adapter.configurado()).isTrue();
    }

    @Test
    void configuradoReturnsFalseWhenApiKeyIsNull() throws Exception {
        var field = adapter.getClass().getDeclaredField("apiKey");
        field.setAccessible(true);
        field.set(adapter, null);

        assertThat(adapter.configurado()).isFalse();
    }

    @Test
    void configuradoReturnsFalseWhenApiKeyIsBlank() throws Exception {
        var field = adapter.getClass().getDeclaredField("apiKey");
        field.setAccessible(true);
        field.set(adapter, "");

        assertThat(adapter.configurado()).isFalse();
    }

    @Test
    void nameReturnsCorrectBrokerName() {
        assertThat(adapter.name()).isEqualTo("infobip");
    }

    @Test
    void configuradoReturnsFalseWhenApiKeyIsWhitespace() throws Exception {
        var field = adapter.getClass().getDeclaredField("apiKey");
        field.setAccessible(true);
        field.set(adapter, "   ");

        assertThat(adapter.configurado()).isFalse();
    }
}

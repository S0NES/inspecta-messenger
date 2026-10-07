package br.com.vistoria.messenger.adapters;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Infobip SMS adapter — stub/not implemented.
 *
 * <p>Per T4.2 verification: Infobip adapter is documented as a stub in README.md.
 * SMS is not on the critical path (email via Resend is the primary channel).
 * If SMS integration becomes required in the future, this stub can be replaced
 * with a real Infobip API client implementation.</p>
 *
 * <p>Until then, the fallback behavior ensures graceful degradation:
 * Dispatcher.dispatch() checks configurado() and falls back to MockAdapter
 * if API key is not configured or if send() would throw.</p>
 */
@Component
public class InfobipAdapter implements BrokerAdapter {

    @Value("${inspecta.notify.infobip-api-key:}") private String apiKey;

    @Override public String name() { return "infobip"; }

    public boolean configurado() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public String send(Rendered msg) {
        throw new UnsupportedOperationException("Infobip SMS integration not implemented (stub)");
    }
}

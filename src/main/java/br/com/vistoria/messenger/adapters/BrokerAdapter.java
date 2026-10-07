package br.com.vistoria.messenger.adapters;

import java.util.Map;

public interface BrokerAdapter {

    /**
     * {@code idempotencyKey} — chave estavel da entrega (o eventId do outbox). Adapters cujo
     * provider suporta idempotencia (Resend) a repassam no header proprio, tornando todo retry
     * (loop do {@code Dispatcher}, redelivery do RabbitMQ, replay de DLQ) seguro contra
     * duplicata. Pode ser {@code null} em call sites legados ou de teste.
     */
    record Rendered(String canal, String destinatario, String assunto, String corpo,
                    Map<String, Object> variaveis, String idempotencyKey) {

        public Rendered(String canal, String destinatario, String assunto, String corpo,
                        Map<String, Object> variaveis) {
            this(canal, destinatario, assunto, corpo, variaveis, null);
        }
    }

    String name();

    String send(Rendered msg) throws Exception;
}

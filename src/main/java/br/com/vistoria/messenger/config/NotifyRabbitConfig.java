package br.com.vistoria.messenger.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

@Configuration
public class NotifyRabbitConfig {

    @Bean
    public Declarables qNotify(TopicExchange ex) {
        Queue dlq = QueueBuilder.durable("q.notify.dlq").build();
        Queue q = QueueBuilder.durable("q.notify")
                .withArguments(Map.of(
                        "x-dead-letter-exchange", "",
                        "x-dead-letter-routing-key", "q.notify.dlq"))
                .build();
        return new Declarables(dlq, q, BindingBuilder.bind(q).to(ex).with("notify.requested.#"));
    }

    @Bean
    public Declarables qStorage(TopicExchange ex) {
        Queue dlq = QueueBuilder.durable("q.storage.dlq").build();
        Queue q = QueueBuilder.durable("q.storage")
                .withArguments(Map.of(
                        "x-dead-letter-exchange", "",
                        "x-dead-letter-routing-key", "q.storage.dlq"))
                .build();
        return new Declarables(dlq, q, BindingBuilder.bind(q).to(ex).with("jobs.storage.#"));
    }

    /** Assinatura: o inspecta-messenger monta+envia o createDocument e processa o webhook (Autentique). */
    private Declarables filaSignature(TopicExchange ex, String nome, String bindingPattern) {
        Queue dlq = QueueBuilder.durable(nome + ".dlq").build();
        Queue q = QueueBuilder.durable(nome)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", "",
                        "x-dead-letter-routing-key", nome + ".dlq"))
                .build();
        return new Declarables(dlq, q, BindingBuilder.bind(q).to(ex).with(bindingPattern));
    }

    @Bean
    public Declarables qSignatureCreate(TopicExchange ex) {
        return filaSignature(ex, "q.signature-create", "jobs.signature.create-document");
    }

    @Bean
    public Declarables qSignatureWebhook(TopicExchange ex) {
        return filaSignature(ex, "q.signature-webhook", "jobs.signature.webhook-received");
    }

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate template = new RetryTemplate();

        // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 64s (7 attempts total)
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1000);      // 1 second initial
        backoff.setMultiplier(2.0);            // double each time
        backoff.setMaxInterval(64000);         // cap at 64 seconds
        template.setBackOffPolicy(backoff);

        // Retry up to 7 times (initial + 6 retries = 7 total attempts)
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(7);
        template.setRetryPolicy(retryPolicy);

        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            RetryTemplate retryTemplate) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);

        // Explicit overrides after Boot's configurer: disable requeue on rejection (per-queue DLQ instead)
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);

        // q.notify processava com 1 consumidor: um único e-mail lento (ou preso no retry
        // exponencial de até ~127s abaixo) segurava toda a fila, então uma redefinição de
        // senha podia demorar minutos pra sair. Pool pequeno de consumidores => uma mensagem
        // travada não bloqueia as outras. prefetch=1 mantém o fair inspecta-messenger.
        f.setConcurrentConsumers(3);
        f.setMaxConcurrentConsumers(8);

        // Apply exponential backoff retry (7 attempts): 1s → 2s → 4s → 8s → 16s → 32s → 64s
        // After exhaustion, exception is thrown; defaultRequeueRejected=false routes to DLQ
        f.setRetryTemplate(retryTemplate);

        return f;
    }

    /**
     * q.storage consumer (ArquivoStorageConsumer): fala com sistema externo (S3/MinIO/R2), que
     * pode falhar transitoriamente — mesmo tratamento de retry das filas de inspecta-worker
     * (JobsRabbitConfig) para integracoes externas.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory storageListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            RetryTemplate retryTemplate) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);
        f.setRetryTemplate(retryTemplate);
        return f;
    }

    /**
     * q.signature-create / q.signature-webhook: falam com a Autentique (HTTP). Mesmo tratamento
     * de retry das demais integracoes externas. Replay guard ({@code documentoJaConcluido} /
     * dedup do webhook) garante idempotencia.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory signatureListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            RetryTemplate retryTemplate) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);
        f.setRetryTemplate(retryTemplate);
        return f;
    }
}

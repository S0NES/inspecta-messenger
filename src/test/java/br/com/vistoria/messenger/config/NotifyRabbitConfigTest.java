package br.com.vistoria.messenger.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link NotifyRabbitConfig}: verifies the DLQ routing declared for
 * {@code q.notify} (dead-letter exchange/routing-key arguments, DLQ durability, and the
 * topic binding) and the exponential-backoff retry policy applied at the listener
 * container level. Exercises the {@code @Bean} factory methods directly (no Spring
 * context) so the retry policy's real 1s..64s backoff is never actually slept through.
 */
class NotifyRabbitConfigTest {

    private final NotifyRabbitConfig config = new NotifyRabbitConfig();

    @SuppressWarnings("unchecked")
    private <T extends Declarable> List<T> declarablesOfType(Declarables declarables, Class<T> type) {
        Collection<Declarable> all = declarables.getDeclarables();
        return all.stream()
                .filter(type::isInstance)
                .map(d -> (T) d)
                .toList();
    }

    @Test
    void qNotifyDeclaresMainQueueWithDeadLetterRoutingToDlq() {
        Declarables declarables = config.qNotify(new TopicExchange("inspecta.events"));

        Queue main = declarablesOfType(declarables, Queue.class).stream()
                .filter(q -> q.getName().equals("q.notify"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("q.notify not declared"));

        assertThat(main.isDurable()).isTrue();
        assertThat(main.getArguments())
                .containsEntry("x-dead-letter-exchange", "")
                .containsEntry("x-dead-letter-routing-key", "q.notify.dlq");
    }

    @Test
    void qNotifyDeclaresDurableDlq() {
        Declarables declarables = config.qNotify(new TopicExchange("inspecta.events"));

        Queue dlq = declarablesOfType(declarables, Queue.class).stream()
                .filter(q -> q.getName().equals("q.notify.dlq"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("q.notify.dlq not declared"));

        assertThat(dlq.isDurable()).isTrue();
        // The DLQ itself must not point anywhere further (it's the terminal sink).
        assertThat(dlq.getArguments()).doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    void qNotifyBindsMainQueueToEventsExchangeWithNotifyRequestedRoutingKey() {
        TopicExchange exchange = new TopicExchange("inspecta.events");
        Declarables declarables = config.qNotify(exchange);

        Binding binding = declarablesOfType(declarables, Binding.class).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("no binding declared"));

        assertThat(binding.getExchange()).isEqualTo("inspecta.events");
        assertThat(binding.getDestination()).isEqualTo("q.notify");
        assertThat(binding.getRoutingKey()).isEqualTo("notify.requested.#");
    }

    @Test
    void retryTemplateAllowsSevenAttemptsBeforeGivingUp() throws Exception {
        RetryTemplate template = config.retryTemplate();

        var policyField = RetryTemplate.class.getDeclaredField("retryPolicy");
        policyField.setAccessible(true);
        SimpleRetryPolicy policy = (SimpleRetryPolicy) policyField.get(template);

        assertThat(policy.getMaxAttempts()).isEqualTo(7);
    }

    @Test
    void retryTemplateUsesExponentialBackoffFromOneSecondCappedAtSixtyFourSeconds() throws Exception {
        RetryTemplate template = config.retryTemplate();

        var backoffField = RetryTemplate.class.getDeclaredField("backOffPolicy");
        backoffField.setAccessible(true);
        ExponentialBackOffPolicy backoff = (ExponentialBackOffPolicy) backoffField.get(template);

        assertThat(backoff.getInitialInterval()).isEqualTo(1000L);
        assertThat(backoff.getMultiplier()).isEqualTo(2.0d);
        assertThat(backoff.getMaxInterval()).isEqualTo(64000L);
    }

    @Test
    void rabbitListenerContainerFactoryDisablesRequeueAndAppliesPrefetchAndRetryTemplate() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        SimpleRabbitListenerContainerFactoryConfigurer configurer = mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        RetryTemplate retryTemplate = config.retryTemplate();

        SimpleRabbitListenerContainerFactory factory =
                config.rabbitListenerContainerFactory(connectionFactory, configurer, retryTemplate);

        // Boot's configurer must be applied first so our overrides win.
        verify(configurer).configure(same(factory), same(connectionFactory));

        assertThat(factory).extracting("defaultRequeueRejected").isEqualTo(false);
        assertThat(factory).extracting("prefetchCount").isEqualTo(1);
        assertThat(factory).extracting("retryTemplate").isEqualTo(retryTemplate);
    }

    @Test
    void qStorageDeclaresMainQueueWithDeadLetterRoutingToDlq() {
        Declarables declarables = config.qStorage(new TopicExchange("inspecta.events"));

        Queue main = declarablesOfType(declarables, Queue.class).stream()
                .filter(q -> q.getName().equals("q.storage"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("q.storage not declared"));

        assertThat(main.isDurable()).isTrue();
        assertThat(main.getArguments())
                .containsEntry("x-dead-letter-exchange", "")
                .containsEntry("x-dead-letter-routing-key", "q.storage.dlq");
    }

    @Test
    void qStorageDeclaresDurableDlq() {
        Declarables declarables = config.qStorage(new TopicExchange("inspecta.events"));

        Queue dlq = declarablesOfType(declarables, Queue.class).stream()
                .filter(q -> q.getName().equals("q.storage.dlq"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("q.storage.dlq not declared"));

        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.getArguments()).doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    void qStorageBindsMainQueueToEventsExchangeWithJobsStorageRoutingKey() {
        TopicExchange exchange = new TopicExchange("inspecta.events");
        Declarables declarables = config.qStorage(exchange);

        Binding binding = declarablesOfType(declarables, Binding.class).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("no binding declared"));

        assertThat(binding.getExchange()).isEqualTo("inspecta.events");
        assertThat(binding.getDestination()).isEqualTo("q.storage");
        assertThat(binding.getRoutingKey()).isEqualTo("jobs.storage.#");
    }

    @Test
    void storageListenerFactoryDisablesRequeueAndAppliesPrefetchAndRetryTemplate() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        SimpleRabbitListenerContainerFactoryConfigurer configurer = mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        RetryTemplate retryTemplate = config.retryTemplate();

        SimpleRabbitListenerContainerFactory factory =
                config.storageListenerFactory(connectionFactory, configurer, retryTemplate);

        verify(configurer).configure(same(factory), same(connectionFactory));

        assertThat(factory).extracting("defaultRequeueRejected").isEqualTo(false);
        assertThat(factory).extracting("prefetchCount").isEqualTo(1);
        assertThat(factory).extracting("retryTemplate").isEqualTo(retryTemplate);
    }
}

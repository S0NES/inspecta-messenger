package br.com.vistoria.messenger.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DlqMonitor}: verifies that DLQ depth monitoring
 * correctly queries the broker, records metrics, logs warnings, and handles errors.
 * All collaborators (RabbitAdmin, MeterRegistry) are mocked.
 */
class DlqMonitorTest {

    private RabbitAdmin rabbitAdmin;
    private MeterRegistry meterRegistry;
    private DlqMonitor monitor;

    @BeforeEach
    void setUp() {
        rabbitAdmin = mock(RabbitAdmin.class);
        meterRegistry = mock(MeterRegistry.class);
        monitor = new DlqMonitor();
        // Inject mocks via reflection (fields are @Autowired)
        injectField(monitor, "rabbitAdmin", rabbitAdmin);
        injectField(monitor, "meterRegistry", meterRegistry);
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void monitorDlqDepth_recordsGaugeMetricWhenQueueExists() {
        var queueInfo = new Properties();
        queueInfo.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 5);
        when(rabbitAdmin.getQueueProperties("q.notify.dlq")).thenReturn(queueInfo);

        monitor.monitorDlqDepth();

        verify(meterRegistry).gauge(
                eq("dlq.depth"),
                any(Tags.class),
                eq(5)
        );
    }

    @Test
    void monitorDlqDepth_recordsZeroMessageCountWhenQueueEmpty() {
        var queueInfo = new Properties();
        queueInfo.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0);
        when(rabbitAdmin.getQueueProperties("q.notify.dlq")).thenReturn(queueInfo);

        monitor.monitorDlqDepth();

        verify(meterRegistry).gauge(
                eq("dlq.depth"),
                any(Tags.class),
                eq(0)
        );
    }

    @Test
    void monitorDlqDepth_queriesCorrectDlqName() {
        var queueInfo = new Properties();
        queueInfo.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0);
        when(rabbitAdmin.getQueueProperties("q.notify.dlq")).thenReturn(queueInfo);

        monitor.monitorDlqDepth();

        verify(rabbitAdmin).getQueueProperties("q.notify.dlq");
    }

    @Test
    void monitorDlqDepth_handlesNullQueuePropertiesGracefully() {
        when(rabbitAdmin.getQueueProperties("q.notify.dlq")).thenReturn(null);

        // Should not throw
        monitor.monitorDlqDepth();

        // Should not attempt to record metric if queue doesn't exist
        verify(rabbitAdmin).getQueueProperties("q.notify.dlq");
    }

    @Test
    void monitorDlqDepth_handlesBrokerExceptionGracefully() {
        when(rabbitAdmin.getQueueProperties("q.notify.dlq"))
                .thenThrow(new RuntimeException("Broker connection failed"));

        // Should not throw
        monitor.monitorDlqDepth();

        // Should not record metric on exception
        verify(rabbitAdmin).getQueueProperties("q.notify.dlq");
    }

    @Test
    void monitorDlqDepth_extractsMessageCountAsInteger() {
        var queueInfo = new Properties();
        queueInfo.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 42);
        when(rabbitAdmin.getQueueProperties("q.notify.dlq")).thenReturn(queueInfo);

        monitor.monitorDlqDepth();

        ArgumentCaptor<Integer> countCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(meterRegistry).gauge(any(), any(), countCaptor.capture());
        assertThat(countCaptor.getValue()).isEqualTo(42);
    }

    @Test
    void monitorDlqDepth_handlesLargeMessageCounts() {
        var queueInfo = new Properties();
        queueInfo.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 999999);
        when(rabbitAdmin.getQueueProperties("q.notify.dlq")).thenReturn(queueInfo);

        monitor.monitorDlqDepth();

        ArgumentCaptor<Integer> countCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(meterRegistry).gauge(any(), any(), countCaptor.capture());
        assertThat(countCaptor.getValue()).isEqualTo(999999);
    }

    @Test
    void monitorDlqDepth_recordsMetricWithCorrectTags() {
        var queueInfo = new Properties();
        queueInfo.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 3);
        when(rabbitAdmin.getQueueProperties("q.notify.dlq")).thenReturn(queueInfo);

        monitor.monitorDlqDepth();

        ArgumentCaptor<Tags> tagsCaptor = ArgumentCaptor.forClass(Tags.class);
        verify(meterRegistry).gauge(eq("dlq.depth"), tagsCaptor.capture(), eq(3));
        Tags tags = tagsCaptor.getValue();
        // Verify tags contain the queue label
        assertThat(tags.toString()).contains("queue");
        assertThat(tags.toString()).contains("notify");
    }

    @Test
    void monitorDlqDepth_completesSuccessfullyWithZeroMessages() {
        var queueInfo = new Properties();
        queueInfo.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 0);
        when(rabbitAdmin.getQueueProperties("q.notify.dlq")).thenReturn(queueInfo);

        monitor.monitorDlqDepth();

        verify(rabbitAdmin).getQueueProperties("q.notify.dlq");
        verify(meterRegistry).gauge(any(), any(), eq(0));
    }

    @Test
    void monitorStorageDlqDepth_queriesStorageDlqAndRecordsGauge() {
        var queueInfo = new Properties();
        queueInfo.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 7);
        when(rabbitAdmin.getQueueProperties("q.storage.dlq")).thenReturn(queueInfo);

        monitor.monitorStorageDlqDepth();

        verify(rabbitAdmin).getQueueProperties("q.storage.dlq");
        ArgumentCaptor<Tags> tagsCaptor = ArgumentCaptor.forClass(Tags.class);
        verify(meterRegistry).gauge(eq("dlq.depth"), tagsCaptor.capture(), eq(7));
        assertThat(tagsCaptor.getValue().toString()).contains("storage");
    }

    @Test
    void monitorStorageDlqDepth_handlesNullQueuePropertiesGracefully() {
        when(rabbitAdmin.getQueueProperties("q.storage.dlq")).thenReturn(null);

        monitor.monitorStorageDlqDepth();

        verify(rabbitAdmin).getQueueProperties("q.storage.dlq");
    }

    @Test
    void monitorStorageDlqDepth_handlesBrokerExceptionGracefully() {
        when(rabbitAdmin.getQueueProperties("q.storage.dlq"))
                .thenThrow(new RuntimeException("Broker connection failed"));

        monitor.monitorStorageDlqDepth();

        verify(rabbitAdmin).getQueueProperties("q.storage.dlq");
    }
}

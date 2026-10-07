package br.com.vistoria.messenger.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitors Dead Letter Queue (DLQ) depth for inspecta-messenger notification queue.
 *
 * <h3>DLQ Drain/Replay Procedure</h3>
 * <p>When messages accumulate in a DLQ (detected by non-zero gauge), follow these steps:</p>
 * <ol>
 *   <li>Check the {@code dlq.depth} metric for q.notify.dlq</li>
 *   <li>SSH into a pod: {@code kubectl exec -it inspecta-messenger-<pod> -- bash}</li>
 *   <li>Query the DLQ: {@code rabbitmqctl list_queues name messages | grep q.notify.dlq}</li>
 *   <li>If messages require investigation, export them first:
 *     {@code rabbitmq-queues drain_queue q.notify.dlq <output-file>}</li>
 *   <li>Root cause the failure (check pod logs, database state, external broker status)</li>
 *   <li>Fix the root cause (code deploy, data correction, or wait for broker recovery)</li>
 *   <li>Replay messages by publishing them back to the main queue or deleting if unrecoverable</li>
 * </ol>
 * <p>Example replay (after fix): {@code rabbitmq-queues requeue-all q.notify.dlq q.notify}</p>
 *
 * <h3>Alerting</h3>
 * <p>Configure your monitoring system (Prometheus, Datadog, etc.) to alert when
 * {@code dlq.depth} metric > 0 for more than 5 minutes. DLQ messages indicate either
 * a bug that needs immediate attention or external broker/messaging provider failure.</p>
 */
@Component
@EnableScheduling
@ConditionalOnClass(RabbitAdmin.class)
public class DlqMonitor {

    private static final Logger log = LoggerFactory.getLogger(DlqMonitor.class);
    private static final String DLQ_NAME = "q.notify.dlq";
    private static final String STORAGE_DLQ_NAME = "q.storage.dlq";
    private static final String SIGNATURE_CREATE_DLQ_NAME = "q.signature-create.dlq";
    private static final String SIGNATURE_WEBHOOK_DLQ_NAME = "q.signature-webhook.dlq";

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @Autowired
    private MeterRegistry meterRegistry;

    @Scheduled(fixedDelay = 60000)  // Check DLQ depth every 60 seconds
    public void monitorDlqDepth() {
        checkDlq(DLQ_NAME, "notify");
    }

    @Scheduled(fixedDelay = 60000)  // Check DLQ depth every 60 seconds
    public void monitorStorageDlqDepth() {
        checkDlq(STORAGE_DLQ_NAME, "storage");
    }

    @Scheduled(fixedDelay = 60000)
    public void monitorSignatureDlqDepth() {
        checkDlq(SIGNATURE_CREATE_DLQ_NAME, "signature-create");
        checkDlq(SIGNATURE_WEBHOOK_DLQ_NAME, "signature-webhook");
    }

    private void checkDlq(String dlqName, String tagName) {
        try {
            var queueInfo = rabbitAdmin.getQueueProperties(dlqName);
            if (queueInfo != null) {
                int messageCount = (int) queueInfo.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
                meterRegistry.gauge("dlq.depth", Tags.of("queue", tagName), messageCount);

                if (messageCount > 0) {
                    log.warn("DLQ {} has {} messages", dlqName, messageCount);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query DLQ {}: {}", dlqName, e.getMessage());
        }
    }
}

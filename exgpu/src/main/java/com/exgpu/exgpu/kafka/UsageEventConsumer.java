package com.exgpu.exgpu.kafka;

import com.exgpu.exgpu.dto.SubmitUsageEventRequest;
import com.exgpu.exgpu.dto.UsageEventResponse;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.service.BillingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class UsageEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UsageEventConsumer.class);

    private final BillingService billingService;
    private final KafkaTemplate<String, DlqMessage> dlqTemplate;
    private final ObjectMapper objectMapper;
    private final ExgpuMetrics metrics;
    private final String dlqTopic;

    public UsageEventConsumer(BillingService billingService,
                              KafkaTemplate<String, DlqMessage> dlqTemplate,
                              ObjectMapper objectMapper,
                              ExgpuMetrics metrics,
                              @Value("${exgpu.kafka.usage-events-dlq:exgpu.usage-events.dlq}") String dlqTopic) {
        this.billingService = billingService;
        this.dlqTemplate = dlqTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.dlqTopic = dlqTopic;
    }

    @KafkaListener(topics = "${exgpu.kafka.usage-events-topic}")
    public void consume(ConsumerRecord<String, UsageEventMessage> record) {
        UsageEventMessage message = record.value();

        if (message == null) {
            sendToDlq(null, null, "DESERIALIZATION_FAILURE",
                    "Message at partition=" + record.partition() + " offset=" + record.offset()
                            + " could not be deserialized");
            return;
        }

        try {
            SubmitUsageEventRequest request = new SubmitUsageEventRequest(
                    message.eventId(),
                    message.allocationId(),
                    message.usageSeconds()
            );
            UsageEventResponse response = billingService.submitUsageEvent(request);

            if (response.duplicate()) {
                log.info("Duplicate usage event skipped: eventId={}", message.eventId());
            } else {
                log.info("Usage event billed: eventId={} cost={} remaining={} computeKilled={}",
                        message.eventId(), response.cost(), response.remainingBalance(), response.computeKilled());
            }
        } catch (Exception e) {
            sendToDlq(message.eventId(), toJson(message), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void sendToDlq(String eventId, String payload, String errorType, String errorMessage) {
        DlqMessage dlq = new DlqMessage(eventId, payload, errorType, errorMessage, Instant.now().toString());
        metrics.incrementUsageEventsDlq();

        // Deliberately not published over WebSocket. A DLQ message carries the raw rejected
        // payload and the exception text, and a failed event has no reliably-known owner to
        // address it to — so there is no destination it could go to without either leaking
        // one user's data to another or broadcasting internals to everyone. The
        // exgpu_usage_events_dlq counter and the log line below are the operator's view.
        dlqTemplate.send(dlqTopic, eventId, dlq)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish to DLQ: eventId={} error={}", eventId, ex.getMessage());
                    } else {
                        log.warn("Sent to DLQ: eventId={} errorType={} errorMessage={}",
                                eventId, errorType, errorMessage);
                    }
                });
    }

    private String toJson(UsageEventMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            return "<serialization failed>";
        }
    }
}

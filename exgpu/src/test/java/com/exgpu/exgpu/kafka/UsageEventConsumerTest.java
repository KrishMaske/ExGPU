package com.exgpu.exgpu.kafka;

import com.exgpu.exgpu.dto.UsageEventResponse;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.service.BillingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class UsageEventConsumerTest {

    private BillingService billingService;
    private KafkaTemplate<String, DlqMessage> dlqTemplate;
    private UsageEventConsumer consumer;

    private static final String DLQ_TOPIC = "exgpu.usage-events.dlq";

    @BeforeEach
    void setUp() {
        billingService = mock(BillingService.class);
        dlqTemplate    = mock(KafkaTemplate.class);
        when(dlqTemplate.send(anyString(), nullable(String.class), any(DlqMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        consumer = new UsageEventConsumer(billingService, dlqTemplate, new ObjectMapper(),
                new ExgpuMetrics(new SimpleMeterRegistry()), DLQ_TOPIC);
    }

    private ConsumerRecord<String, UsageEventMessage> record(UsageEventMessage message) {
        return new ConsumerRecord<>("exgpu.usage-events", 0, 0L, null, message);
    }

    private UsageEventResponse successResponse() {
        return new UsageEventResponse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                3600, BigDecimal.valueOf(2), BigDecimal.valueOf(98), false, false, Instant.now());
    }

    private UsageEventResponse duplicateResponse() {
        return new UsageEventResponse(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                3600, BigDecimal.valueOf(2), BigDecimal.valueOf(98), false, true, Instant.now());
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void consume_validMessage_callsBillingServiceAndDoesNotSendToDlq() {
        UsageEventMessage message = new UsageEventMessage("evt-ok", UUID.randomUUID(), 3600);
        when(billingService.submitUsageEvent(any())).thenReturn(successResponse());

        consumer.consume(record(message));

        verify(billingService).submitUsageEvent(any());
        verify(dlqTemplate, never()).send(anyString(), anyString(), any(DlqMessage.class));
    }

    // ── duplicate path — must NOT go to DLQ ──────────────────────────────────

    @Test
    void consume_duplicateMessage_doesNotSendToDlq() {
        UsageEventMessage message = new UsageEventMessage("evt-dup", UUID.randomUUID(), 3600);
        when(billingService.submitUsageEvent(any())).thenReturn(duplicateResponse());

        consumer.consume(record(message));

        verify(dlqTemplate, never()).send(anyString(), anyString(), any(DlqMessage.class));
    }

    // ── deserialization failure — null value ──────────────────────────────────

    @Test
    void consume_nullValue_sendsToDlqAndDoesNotCallBillingService() {
        ConsumerRecord<String, UsageEventMessage> record =
                new ConsumerRecord<>("exgpu.usage-events", 0, 7L, null, null);

        consumer.consume(record);

        verify(billingService, never()).submitUsageEvent(any());

        ArgumentCaptor<DlqMessage> captor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(dlqTemplate).send(eq(DLQ_TOPIC), (String) eq(null), captor.capture());

        DlqMessage dlq = captor.getValue();
        assertThat(dlq.eventId()).isNull();
        assertThat(dlq.errorType()).isEqualTo("DESERIALIZATION_FAILURE");
        assertThat(dlq.failedAt()).isNotNull();
    }

    // ── billing failure — must go to DLQ ─────────────────────────────────────

    @Test
    void consume_allocationNotFound_sendsToDlqWithEventId() {
        String eventId = "evt-no-alloc";
        UsageEventMessage message = new UsageEventMessage(eventId, UUID.randomUUID(), 3600);
        when(billingService.submitUsageEvent(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Allocation not found"));

        assertDoesNotThrow(() -> consumer.consume(record(message)));

        ArgumentCaptor<DlqMessage> captor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(dlqTemplate).send(eq(DLQ_TOPIC), eq(eventId), captor.capture());

        DlqMessage dlq = captor.getValue();
        assertThat(dlq.eventId()).isEqualTo(eventId);
        assertThat(dlq.errorType()).isEqualTo("ResponseStatusException");
        assertThat(dlq.originalPayload()).contains(eventId);
        assertThat(dlq.failedAt()).isNotNull();
    }

    @Test
    void consume_insufficientBalance_sendsToDlq() {
        String eventId = "evt-broke";
        UsageEventMessage message = new UsageEventMessage(eventId, UUID.randomUUID(), 3600);
        when(billingService.submitUsageEvent(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient balance"));

        consumer.consume(record(message));

        ArgumentCaptor<DlqMessage> captor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(dlqTemplate).send(eq(DLQ_TOPIC), eq(eventId), captor.capture());
        assertThat(captor.getValue().errorType()).isEqualTo("ResponseStatusException");
    }

    @Test
    void consume_unexpectedError_sendsToDlqAndDoesNotPropagate() {
        String eventId = "evt-boom";
        UsageEventMessage message = new UsageEventMessage(eventId, UUID.randomUUID(), 3600);
        when(billingService.submitUsageEvent(any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertDoesNotThrow(() -> consumer.consume(record(message)));

        ArgumentCaptor<DlqMessage> captor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(dlqTemplate).send(eq(DLQ_TOPIC), eq(eventId), captor.capture());
        assertThat(captor.getValue().errorType()).isEqualTo("RuntimeException");
        assertThat(captor.getValue().errorMessage()).isEqualTo("DB connection lost");
    }
}

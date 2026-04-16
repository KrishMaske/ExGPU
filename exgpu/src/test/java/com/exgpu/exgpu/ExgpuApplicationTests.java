package com.exgpu.exgpu;

import com.exgpu.exgpu.kafka.DlqMessage;
import com.exgpu.exgpu.repository.AccessLeaseRepository;
import com.exgpu.exgpu.repository.AllocationRepository;
import com.exgpu.exgpu.repository.OrderRepository;
import com.exgpu.exgpu.repository.TokenBalanceRepository;
import com.exgpu.exgpu.repository.UsageLedgerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Boots the real application context.
 *
 * <p>Only the <em>external</em> collaborators are mocked — repositories (Postgres) and the
 * Kafka template. Everything the app builds for itself, {@link com.exgpu.exgpu.engine.MatchingEngine}
 * and its {@link com.exgpu.exgpu.engine.TimeSliceLockManager} included, is constructed for
 * real, so a missing or unsatisfiable bean fails here rather than at {@code spring-boot:run}.
 * Mocking the engine would hide exactly that class of wiring bug.
 */
@SpringBootTest
class ExgpuApplicationTests {

    @MockBean OrderRepository orderRepository;
    @MockBean AllocationRepository allocationRepository;
    @MockBean AccessLeaseRepository accessLeaseRepository;
    @MockBean TokenBalanceRepository tokenBalanceRepository;
    @MockBean UsageLedgerRepository usageLedgerRepository;
    @MockBean KafkaTemplate<String, DlqMessage> dlqTemplate;

    @Test
    void contextLoads() {
    }
}

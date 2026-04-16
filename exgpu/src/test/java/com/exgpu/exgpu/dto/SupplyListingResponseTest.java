package com.exgpu.exgpu.dto;

import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.OrderSide;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5 — the anonymous marketplace projection must not carry a field that correlates one
 * listing back to another, or back to a series/provider. This matters specifically for a
 * recurring listing's children (D7): they are 20 (say) ordinary rows sharing one
 * {@code parentOrderId}, and exposing that id here would be a partial deanonymisation of
 * exactly the thing this projection exists to hide.
 */
class SupplyListingResponseTest {

    @Test
    void from_aRecurringChildOrder_carriesNoParentOrSeriesCorrelatingField() {
        UUID parentId = UUID.randomUUID();
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS);
        Order child = Order.builder()
                .id(UUID.randomUUID())
                .ownerId(UUID.randomUUID())
                .side(OrderSide.SELL)
                .pricePerGpuHour(BigDecimal.ONE)
                .quantity(5)
                .window(new TimeWindow(start, start.plus(1, ChronoUnit.HOURS)))
                .parentOrderId(parentId)
                .recurring(false)
                .priorityTimestamp(Instant.now())
                .build();

        SupplyListingResponse response = SupplyListingResponse.from(child);

        assertThat(response.toString()).doesNotContain(parentId.toString());

        // Structural guard, not just a value check: no component of this record may ever be
        // named/typed in a way that could carry a parent/series/owner correlation, even if a
        // future edit forgets to populate it from the order.
        for (RecordComponent component : SupplyListingResponse.class.getRecordComponents()) {
            assertThat(component.getName().toLowerCase())
                    .as("SupplyListingResponse component '%s' must not correlate listings", component.getName())
                    .doesNotContain("parent")
                    .doesNotContain("series")
                    .doesNotContain("owner")
                    .doesNotContain("seller")
                    .doesNotContain("provider");
        }
    }

    @Test
    void from_neverExposesOwnerId() {
        assertThat(Arrays.stream(SupplyListingResponse.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("ownerId");
    }
}

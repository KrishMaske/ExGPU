package com.exgpu.exgpu.metrics;

import com.exgpu.exgpu.engine.MatchingEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Book-depth gauges, bound to {@link MatchingEngine#buyBookSize()} / {@link
 * MatchingEngine#sellBookSize()} — previously computed but exposed nowhere. {@code CLAUDE.md}
 * names order book depth explicitly in its observability list.
 *
 * <p>A separate component from {@link ExgpuMetrics} on purpose: it keeps {@code ExgpuMetrics}
 * free of a {@code MatchingEngine} dependency, so services that only need counters/timers are
 * not pulled into a dependency on the matching engine.
 */
@Component
public class OrderBookMetrics {

    public OrderBookMetrics(MeterRegistry registry, MatchingEngine matchingEngine) {
        Gauge.builder("exgpu_order_book_depth", matchingEngine, MatchingEngine::buyBookSize)
                .description("Resting orders currently in the book")
                .tag("side", "BUY")
                .register(registry);
        Gauge.builder("exgpu_order_book_depth", matchingEngine, MatchingEngine::sellBookSize)
                .description("Resting orders currently in the book")
                .tag("side", "SELL")
                .register(registry);
    }
}

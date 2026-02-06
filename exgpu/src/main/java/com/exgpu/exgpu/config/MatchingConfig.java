package com.exgpu.exgpu.config;

import com.exgpu.exgpu.engine.TimeSliceLockManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the matching engine's locking primitive.
 *
 * <p>{@link TimeSliceLockManager} is deliberately annotation-free — it is a plain concurrency
 * class with plain-JUnit tests — so its bean definition lives here rather than as a stereotype
 * on the class itself.
 *
 * <p>{@link com.exgpu.exgpu.engine.MatchingEngine} takes the manager as a required constructor
 * dependency in <em>both</em> locking modes, so this bean must exist even when
 * {@code exgpu.matching.striped=false}; without it the context fails to start.
 */
@Configuration
public class MatchingConfig {

    /**
     * Sizes the two stripe arrays from configuration. Both counts must be powers of two — the
     * manager masks rather than modulos to index them — and its constructor rejects anything
     * else, so a bad value fails fast at startup instead of silently skewing lock distribution.
     *
     * <p>Defaults here mirror the manager's own, so the bean is still correctly sized if the
     * properties are absent (as they are in the test classpath).
     */
    @Bean
    TimeSliceLockManager timeSliceLockManager(
            @Value("${exgpu.matching.lock-stripes:1024}") int lockStripes,
            @Value("${exgpu.matching.order-lock-stripes:512}") int orderLockStripes) {
        return new TimeSliceLockManager(lockStripes, orderLockStripes);
    }
}

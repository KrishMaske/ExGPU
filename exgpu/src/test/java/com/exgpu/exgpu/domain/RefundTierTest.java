package com.exgpu.exgpu.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.exgpu.exgpu.domain.enums.RefundTier;

/**
 * The refund policy: full at 8h+ notice, half between 4h and 8h, nothing under 4h.
 *
 * <p>Boundaries are tested from both sides, because "8 hours" is ambiguous about whether
 * exactly 8 hours qualifies — here it does, so a buyer on the threshold is not penalised by a
 * second of latency.
 */
class RefundTierTest {

    private static final Instant WINDOW_START = Instant.parse("2026-06-01T15:00:00Z");

    private RefundTier at(Duration notice) {
        return RefundTier.forNotice(WINDOW_START.minus(notice), WINDOW_START);
    }

    // ── full refund ───────────────────────────────────────────────────────────

    @Test
    void wellOverEightHours_isFullRefund() {
        assertThat(at(Duration.ofHours(24))).isEqualTo(RefundTier.FULL);
    }

    @Test
    void exactlyEightHours_isFullRefund() {
        assertThat(at(Duration.ofHours(8))).isEqualTo(RefundTier.FULL);
    }

    @Test
    void oneSecondUnderEightHours_dropsToPartial() {
        assertThat(at(Duration.ofHours(8).minusSeconds(1))).isEqualTo(RefundTier.PARTIAL);
    }

    // ── partial refund ────────────────────────────────────────────────────────

    @Test
    void betweenFourAndEightHours_isPartial() {
        assertThat(at(Duration.ofHours(6))).isEqualTo(RefundTier.PARTIAL);
    }

    @Test
    void exactlyFourHours_isStillPartial() {
        assertThat(at(Duration.ofHours(4))).isEqualTo(RefundTier.PARTIAL);
    }

    @Test
    void oneSecondUnderFourHours_dropsToNone() {
        assertThat(at(Duration.ofHours(4).minusSeconds(1))).isEqualTo(RefundTier.NONE);
    }

    // ── no refund ─────────────────────────────────────────────────────────────

    @Test
    void underFourHours_isNoRefund() {
        assertThat(at(Duration.ofHours(1))).isEqualTo(RefundTier.NONE);
    }

    /** A window that has already opened cannot clear any notice threshold. */
    @Test
    void afterWindowHasStarted_isNoRefund() {
        assertThat(RefundTier.forNotice(WINDOW_START.plusSeconds(1), WINDOW_START))
                .isEqualTo(RefundTier.NONE);
        assertThat(RefundTier.forNotice(WINDOW_START.plus(Duration.ofHours(3)), WINDOW_START))
                .isEqualTo(RefundTier.NONE);
    }

    // ── rates ─────────────────────────────────────────────────────────────────

    @Test
    void ratesAreFullHalfAndNothing() {
        assertThat(RefundTier.FULL.rate()).isEqualByComparingTo("1.00");
        assertThat(RefundTier.PARTIAL.rate()).isEqualByComparingTo("0.50");
        assertThat(RefundTier.NONE.rate()).isEqualByComparingTo("0");
    }
}

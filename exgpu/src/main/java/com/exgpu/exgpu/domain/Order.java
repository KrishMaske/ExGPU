package com.exgpu.exgpu.domain;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Setter;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;


@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name="id", updatable=false, nullable=false)
    private UUID id;

    @Column(name="owner_id", nullable=false)
    private UUID ownerId;
    
    @Enumerated(EnumType.STRING)
    @Column(name="side", nullable=false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name="status", nullable=false)
    @Builder.Default
    private OrderStatus status = OrderStatus.OPEN;

    @Column(name="price_per_gpu_hr", nullable = false, precision = 10, scale = 4)
    private BigDecimal pricePerGpuHour;

    @Column(name="quantity", nullable=false)
    private int quantity;

    @Column(name="filled_quantity", nullable=false)
    @Builder.Default
    private int filledQuantity = 0;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "start", column = @Column(name = "window_start", nullable = false)),
        @AttributeOverride(name = "end",   column = @Column(name = "window_end",   nullable = false))
    })
    private TimeWindow window;

    @Column(name = "recurring", nullable = false)
    @Builder.Default
    private boolean recurring = false;

    @Column(name = "recurrence_pattern", length = 20)
    private String recurrencePattern;

    /** Number of occurrences requested, {@code TEMPLATE} parents only. Null on children. */
    @Column(name = "recurrence_count")
    private Integer recurrenceCount;

    /** IANA zone id occurrences were expanded in (D7's DST correctness requirement). */
    @Column(name = "recurrence_zone", length = 64)
    private String recurrenceZone;

    /**
     * Set on a child order — points back at the {@code TEMPLATE} parent it was expanded from.
     * Null for an ordinary, non-recurring order and for the parent itself.
     */
    @Column(name = "parent_order_id")
    private UUID parentOrderId;

    @Column(name = "priority_timestamp", nullable = false)
    private Instant priorityTimestamp;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    public int remainingQuantity() {
        return quantity - filledQuantity;
    }

    public boolean isMatchable() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
    }

    /**
     * Recomputes {@code status} from {@code filledQuantity} relative to {@code quantity}.
     *
     * <p>Unlike the matching engine's forward path (where {@code filledQuantity} only ever
     * increases, so it never needs to move back down to {@code OPEN}), this handles both
     * directions — it is what compensating rollback (D8), a single dropped fill (D9/D10), and
     * cancellation all need when quantity moves back down as well as up.
     */
    public void recomputeStatus() {
        if (remainingQuantity() <= 0) {
            status = OrderStatus.FILLED;
        } else if (filledQuantity > 0) {
            status = OrderStatus.PARTIALLY_FILLED;
        } else {
            status = OrderStatus.OPEN;
        }
    }

    /**
     * Whether this order's window has already ended, as of {@code now}.
     *
     * <p>Deliberately a named, explicit helper rather than a check baked into
     * {@link #isMatchable()} — see {@code MatchingEngine}'s class Javadoc (D11) for why the
     * clock must stay out of matching. This exists only for the expiry sweep and for callers
     * that need to ask the question directly with a caller-supplied {@code now}.
     */
    public boolean isExpired(Instant now) {
        return !window.getEnd().isAfter(now);
    }
}

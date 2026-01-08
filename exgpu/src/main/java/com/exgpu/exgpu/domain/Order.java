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
    private BigDecimal pricePerGpu;

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

    @Column(name = "priority_timestamp", nullable = false)
    private Instant priorityTimestamp;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public int remainingQuantity() {
        return quantity - filledQuantity;
    }

    public boolean isMatchable() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
    }
}

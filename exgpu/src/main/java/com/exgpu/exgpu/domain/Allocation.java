package com.exgpu.exgpu.domain;
import com.exgpu.exgpu.domain.enums.AllocationStatus;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "allocations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Allocation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name="id", updatable=false, nullable=false)
    private UUID id;

    @Column(name="buy_order_id", nullable=false)
    private UUID buyOrderId;
    
    @Column(name="sell_order_id", nullable=false)
    private UUID sellOrderId;
    
    @Column(name="quantity", nullable=false)
    private int quantity;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "start", column = @Column(name = "window_start", nullable = false)),
        @AttributeOverride(name = "end",   column = @Column(name = "window_end",   nullable = false))
    })
    private TimeWindow window;

    @Enumerated(EnumType.STRING)
    @Column(name="status", nullable=false)
    @Builder.Default
    private AllocationStatus status = AllocationStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;


    @Override
    public String toString() {
        return "Allocation{" +
                "id=" + id +
                ", buyOrderId=" + buyOrderId +
                ", sellOrderId=" + sellOrderId +
                ", quantity=" + quantity +
                ", window=" + window +
                ", status=" + status +
                '}';
    }
}
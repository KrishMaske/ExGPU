package com.exgpu.exgpu.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "token_balances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TokenBalance {

    @Id
    @Column(name = "buyer_id", updatable = false, nullable = false)
    private UUID buyerId;

    @Column(name = "balance", nullable = false, precision = 18, scale = 6)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void deduct(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("deduction amount must be positive");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalStateException("insufficient balance");
        }
        this.balance = this.balance.subtract(amount);
    }

    public void topUp(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("top up amount must be positive");
        }
        this.balance = this.balance.add(amount);
    }

    public boolean isExhausted() {
        return this.balance.compareTo(BigDecimal.ZERO) <= 0;
    }

    @Override
    public String toString() {
        return "TokenBalance{" +
                "buyerId=" + buyerId +
                ", balance=" + balance +
                ", version=" + version +
                '}';
    }
}
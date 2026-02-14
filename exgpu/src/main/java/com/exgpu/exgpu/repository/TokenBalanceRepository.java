package com.exgpu.exgpu.repository;

import com.exgpu.exgpu.domain.TokenBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TokenBalanceRepository extends JpaRepository<TokenBalance, UUID> {
}

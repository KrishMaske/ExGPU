package com.exgpu.exgpu.service;

import com.exgpu.exgpu.dto.AllocationResponse;
import com.exgpu.exgpu.repository.AllocationRepository;
import com.exgpu.exgpu.repository.OrderRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class AllocationService {

    private final AllocationRepository allocationRepository;
    private final OrderRepository orderRepository;

    public AllocationService(AllocationRepository allocationRepository,
                             OrderRepository orderRepository) {
        this.allocationRepository = allocationRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public List<AllocationResponse> findAll() {
        return allocationRepository.findAll(Sort.by("createdAt"))
                .stream()
                .map(AllocationResponse::from)
                .toList();
    }

    /**
     * Allocations for one order, restricted to orders the caller owns.
     *
     * <p>Both the "order does not exist" and "order belongs to someone else" cases return the
     * same 404. Distinguishing them would turn this endpoint into an oracle for probing which
     * order ids are real.
     */
    @Transactional(readOnly = true)
    public List<AllocationResponse> findByOrderIdForOwner(UUID orderId, UUID ownerId) {
        boolean ownedByCaller = orderRepository.findById(orderId)
                .map(o -> ownerId.equals(o.getOwnerId()))
                .orElse(false);
        if (!ownedByCaller) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderId);
        }
        return allocationRepository.findByOrderId(orderId)
                .stream()
                .map(AllocationResponse::from)
                .toList();
    }

    /** What this user is renting — allocations where they are the buyer. */
    @Transactional(readOnly = true)
    public List<AllocationResponse> findMyRentals(UUID buyerId) {
        return allocationRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId)
                .stream()
                .map(AllocationResponse::from)
                .toList();
    }

    /** What this user is supplying — allocations carved out of their SELL orders. */
    @Transactional(readOnly = true)
    public List<AllocationResponse> findMySupply(UUID sellerId) {
        return allocationRepository.findBySellerId(sellerId)
                .stream()
                .map(AllocationResponse::from)
                .toList();
    }

    /** True when the caller is the buyer or the seller on this allocation. */
    @Transactional(readOnly = true)
    public boolean isPartyTo(UUID allocationId, UUID userId) {
        return allocationRepository.isPartyTo(allocationId, userId);
    }
}

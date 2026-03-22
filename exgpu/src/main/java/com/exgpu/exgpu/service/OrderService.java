package com.exgpu.exgpu.service;

import com.exgpu.exgpu.config.AfterCommit;
import com.exgpu.exgpu.domain.Fill;
import com.exgpu.exgpu.domain.MatchResult;
import com.exgpu.exgpu.domain.Order;
import com.exgpu.exgpu.domain.TimeWindow;
import com.exgpu.exgpu.domain.enums.MatchStatus;
import com.exgpu.exgpu.domain.enums.OrderSide;
import com.exgpu.exgpu.domain.enums.OrderStatus;
import com.exgpu.exgpu.dto.AllocationResponse;
import com.exgpu.exgpu.dto.CreateOrderRequest;
import com.exgpu.exgpu.dto.DemandListingResponse;
import com.exgpu.exgpu.dto.OrderResponse;
import com.exgpu.exgpu.dto.PlaceOrderResponse;
import com.exgpu.exgpu.dto.RecurrenceSpec;
import com.exgpu.exgpu.dto.SupplyListingResponse;
import com.exgpu.exgpu.domain.Allocation;
import com.exgpu.exgpu.engine.MatchingEngine;
import com.exgpu.exgpu.engine.RecurrenceExpander;
import com.exgpu.exgpu.metrics.ExgpuMetrics;
import com.exgpu.exgpu.realtime.RealtimeEventPublisher;
import com.exgpu.exgpu.realtime.RealtimeEventType;
import com.exgpu.exgpu.repository.AllocationRepository;
import com.exgpu.exgpu.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final MatchingEngine matchingEngine;
    private final OrderRepository orderRepository;
    private final AllocationRepository allocationRepository;
    private final ExgpuMetrics metrics;
    private final RealtimeEventPublisher events;
    private final AccessLeaseService accessLeaseService;
    private final BillingService billingService;
    private final int maxWindowHours;
    private final int maxHorizonDays;

    public OrderService(MatchingEngine matchingEngine,
                        OrderRepository orderRepository,
                        AllocationRepository allocationRepository,
                        ExgpuMetrics metrics,
                        RealtimeEventPublisher events,
                        AccessLeaseService accessLeaseService,
                        BillingService billingService,
                        @Value("${exgpu.matching.max-window-hours:24}") int maxWindowHours,
                        @Value("${exgpu.orders.max-horizon-days:90}") int maxHorizonDays) {
        this.matchingEngine = matchingEngine;
        this.orderRepository = orderRepository;
        this.allocationRepository = allocationRepository;
        this.metrics = metrics;
        this.events = events;
        this.accessLeaseService = accessLeaseService;
        this.billingService = billingService;
        this.maxWindowHours = maxWindowHours;
        this.maxHorizonDays = maxHorizonDays;
    }

    /**
     * @param ownerId the authenticated caller, resolved from the verified JWT by the
     *                controller. Never read from the request body — see
     *                {@link com.exgpu.exgpu.config.CurrentUser}.
     *
     * <p><b>Settlement is no longer strictly all-or-nothing.</b> Matching, affordability and
     * persistence are three separate steps (D8/D9/D10): if the buyer of a given allocation is
     * <em>this</em> order's owner and cannot cover it, the whole placement is rejected (402)
     * and every engine-side mutation this call made is compensated via
     * {@link MatchingEngine#rollback} when the transaction rolls back. But if the payer is a
     * <em>resting counterparty</em> who cannot cover their share, only that one allocation is
     * dropped — the counterparty's fill is unwound and their order stays in the book, and this
     * placement otherwise succeeds with fewer fills than it matched. A seller placing a SELL
     * is no longer punished for a stranger's empty wallet.
     */
    @Transactional
    public PlaceOrderResponse placeOrder(CreateOrderRequest request, UUID ownerId) {
        validateWindow(request.startTime(), request.endTime());

        if (request.recurrence() != null) {
            if (request.side() != OrderSide.SELL) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Recurring listings are SELL-only: a recurring BUY would repeatedly "
                                + "charge the buyer without per-occurrence consent");
            }
            return placeRecurringOrder(request, ownerId);
        }

        Order order = Order.builder()
                .ownerId(ownerId)
                .side(request.side())
                .pricePerGpuHour(request.pricePerGpuHour())
                .quantity(request.quantity())
                .window(new TimeWindow(request.startTime(), request.endTime()))
                .priorityTimestamp(Instant.now())
                .build();

        // Persist first: Hibernate assigns UUID and @CreationTimestamp sets createdAt.
        // The returned instance is a managed entity — subsequent mutations within this
        // transaction are auto-flushed on commit.
        Order savedOrder = orderRepository.save(order);
        return matchAndSettle(savedOrder);
    }

    /**
     * Rejects a window that cannot be placed as a single order: not chronological, already in
     * the past, or longer than {@code exgpu.matching.max-window-hours} (default 24h — D3). The
     * cap bounds the tier-1 lock set a striped match acquires; a seller who genuinely wants
     * multi-day availability uses a recurring listing (A4) instead of one long order.
     *
     * <p>Placement-time only: {@code OrderBookRehydrator} accepts any span so a pre-existing
     * longer row is never rejected at startup.
     */
    private void validateWindow(Instant start, Instant end) {
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
        }
        if (!end.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endTime must be in the future");
        }
        if (Duration.between(start, end).compareTo(Duration.ofHours(maxWindowHours)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "window must be " + maxWindowHours
                            + " hours or less; use a recurring listing to offer capacity across multiple days");
        }
    }

    /**
     * Expands a recurring SELL listing into a {@code TEMPLATE} parent plus one concrete child
     * order per occurrence (D6), each submitted through {@link #matchAndSettle} exactly like an
     * ordinary order — no parallel matching/billing/leasing path.
     */
    private PlaceOrderResponse placeRecurringOrder(CreateOrderRequest request, UUID ownerId) {
        RecurrenceSpec spec = request.recurrence();
        ZoneId zone = (spec.zoneId() == null || spec.zoneId().isBlank())
                ? ZoneId.of("UTC") : ZoneId.of(spec.zoneId());

        List<TimeWindow> windows;
        try {
            windows = RecurrenceExpander.expand(
                    request.startTime(), request.endTime(), spec.pattern(), spec.occurrences(), zone);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recurrence: " + e.getMessage());
        }

        Instant envelopeStart = windows.get(0).getStart();
        Instant envelopeEnd = windows.get(windows.size() - 1).getEnd();
        if (Duration.between(envelopeStart, envelopeEnd).toDays() > maxHorizonDays) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Recurring listing spans more than " + maxHorizonDays + " days");
        }

        // The series header: never matchable, never enters the book, never billed — see
        // OrderStatus.TEMPLATE's Javadoc (D6) for why a status rather than a separate table.
        Order parent = Order.builder()
                .ownerId(ownerId)
                .side(OrderSide.SELL)
                .pricePerGpuHour(request.pricePerGpuHour())
                .quantity(request.quantity())
                .window(new TimeWindow(envelopeStart, envelopeEnd))
                .recurring(true)
                .recurrencePattern(spec.pattern().name())
                .recurrenceCount(spec.occurrences())
                .recurrenceZone(zone.getId())
                .status(OrderStatus.TEMPLATE)
                .priorityTimestamp(Instant.now())
                .build();
        Order savedParent = orderRepository.save(parent);

        List<AllocationResponse> allAllocations = new ArrayList<>();
        int totalMatched = 0;
        int totalRequested = 0;

        for (TimeWindow window : windows) {
            Order child = Order.builder()
                    .ownerId(ownerId)
                    .side(OrderSide.SELL)
                    .pricePerGpuHour(request.pricePerGpuHour())
                    .quantity(request.quantity())
                    .window(window)
                    .parentOrderId(savedParent.getId())
                    .priorityTimestamp(Instant.now())
                    .build();
            Order savedChild = orderRepository.save(child);

            PlaceOrderResponse childResult = matchAndSettle(savedChild);
            allAllocations.addAll(childResult.allocations());
            totalMatched += childResult.totalMatchedQuantity();
            totalRequested += request.quantity();
        }

        MatchStatus aggregateStatus = totalMatched == 0
                ? MatchStatus.NO_MATCH
                : (totalMatched >= totalRequested ? MatchStatus.FULL_FILL : MatchStatus.PARTIAL_FILL);

        return new PlaceOrderResponse(
                OrderResponse.from(savedParent), aggregateStatus, totalMatched, allAllocations);
    }

    /**
     * Matches one already-persisted order against the book and settles the result: affordability
     * filtering (D9), conditional counterparty writes (D10), charging, leasing, and — deferred
     * to after the transaction commits — metrics and realtime publication (D8). Shared by
     * ordinary placement and every child of a recurring series.
     */
    private PlaceOrderResponse matchAndSettle(Order savedOrder) {
        MatchResult result = matchingEngine.submitOrder(savedOrder);

        // The compensating-rollback log (D8). Registered once, up front, over a MUTABLE list:
        // as fills are individually dropped and compensated below (D9/D10), they are removed
        // from this list so a LATER, unrelated rollback (e.g. a charge failing further down)
        // does not double-compensate them. Anything still in the list when a rollback actually
        // happens gets unwound; anything already removed was already handled synchronously.
        List<Fill> pendingFills = new ArrayList<>(result.getFills());
        registerCompensatingRollback(savedOrder, pendingFills);

        List<Allocation> allocations = result.getAllocations();
        List<Fill> fills = result.getFills();
        List<Allocation> persisted = new ArrayList<>();

        for (int i = 0; i < allocations.size(); i++) {
            Allocation allocation = allocations.get(i);
            Fill fill = fills.get(i);
            boolean payerIsIncomingOwner = savedOrder.getOwnerId().equals(allocation.getBuyerId());

            if (!billingService.canAffordBooking(allocation)) {
                if (payerIsIncomingOwner) {
                    // You cannot afford what you just asked for. The whole placement fails;
                    // the registered rollback above compensates everything still pending.
                    throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                            "Add tokens before renting: this order could not be fully funded");
                }
                // The resting counterparty can't pay right now. Drop just this allocation —
                // their order is momentarily broke, not ours to evict from the book (D9).
                dropAndCompensate(savedOrder, fill, pendingFills);
                metrics.incrementBookingChargeFailures();
                log.warn("Dropped allocation: counterparty buyer {} could not afford booking "
                                + "(order={}, qty={})",
                        allocation.getBuyerId(), fill.getOrderId(), fill.getQuantityFilled());
                continue;
            }

            // D10: conditional counterparty write, not save() of a detached engine entity.
            int updated = orderRepository.applyFill(
                    fill.getOrderId(), fill.getQuantityFilled(), fill.getQuantityBefore(), fill.getNewStatus());
            if (updated == 0) {
                dropAndCompensate(savedOrder, fill, pendingFills);
                log.warn("Dropped allocation: book/DB disagreed on order {} (expected filledQuantity={})",
                        fill.getOrderId(), fill.getQuantityBefore());
                continue;
            }

            persisted.add(allocation);
        }

        if (!persisted.isEmpty()) {
            allocationRepository.saveAll(persisted);
            for (Allocation allocation : persisted) {
                billingService.chargeForBooking(allocation);
                accessLeaseService.createForAllocation(allocation);
            }
        }

        metrics.incrementOrdersSubmitted();

        List<Order> updatedOrders = result.getUpdatedOrders();
        AfterCommit.run(() -> {
            if (!persisted.isEmpty()) {
                metrics.incrementMatchesCreated();
                metrics.incrementAllocationsCreated(persisted.size());
            }
            publishRealtime(savedOrder, updatedOrders, persisted);
        });

        MatchStatus status = persisted.isEmpty()
                ? MatchStatus.NO_MATCH
                : (savedOrder.isMatchable() ? MatchStatus.PARTIAL_FILL : MatchStatus.FULL_FILL);
        int totalMatched = persisted.stream().mapToInt(Allocation::getQuantity).sum();
        List<AllocationResponse> allocationResponses = persisted.stream().map(AllocationResponse::from).toList();

        return new PlaceOrderResponse(OrderResponse.from(savedOrder), status, totalMatched, allocationResponses);
    }

    /**
     * Registers the D8 compensating rollback: if (and only if) this transaction ends up rolled
     * back, whatever remains in {@code pendingFills} at that moment is unwound in the engine,
     * and {@code savedOrder} is removed from the book entirely.
     *
     * <p>Guarded by {@code isSynchronizationActive()} so this is also unit-testable outside a
     * real transaction — a test can call {@code TransactionSynchronizationManager
     * .initSynchronization()} itself, no DB required.
     */
    private void registerCompensatingRollback(Order savedOrder, List<Fill> pendingFills) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        MatchResult rollbackTarget = MatchResult.builder()
                .fills(pendingFills)
                .incoming(savedOrder)
                .build();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    matchingEngine.rollback(rollbackTarget);
                }
            }
        });
    }

    /**
     * Unwinds one counterparty fill (D9/D10) and reflects the same reduction on the incoming
     * order, which — unlike the counterparty — is a managed JPA entity in this transaction, so
     * its mutation here is flushed by ordinary dirty-checking without an explicit save().
     */
    private void dropAndCompensate(Order savedOrder, Fill fill, List<Fill> pendingFills) {
        matchingEngine.rollback(MatchResult.builder().fills(List.of(fill)).build());

        savedOrder.setFilledQuantity(Math.max(0, savedOrder.getFilledQuantity() - fill.getQuantityFilled()));
        savedOrder.recomputeStatus();
        // Idempotent: puts it back in the book if this reduction makes it matchable again
        // (e.g. it looked fully filled at match time and so was never inserted).
        matchingEngine.restore(savedOrder);

        pendingFills.remove(fill);
    }

    /**
     * Notifies the parties involved. Pure notification — it reads already-computed results
     * and changes no matching state. Deferred to run only after this transaction actually
     * commits (D8): firing it earlier previously meant a rolled-back trade could still
     * broadcast {@code ORDER_FILLED} / {@code ALLOCATION_CREATED} for a trade that never
     * happened.
     *
     * <p>Every event here is addressed to a specific user rather than broadcast. An order's
     * fill and an allocation's terms reveal what someone is buying and at what price, so
     * they go only to the people on that trade. The one public signal is a market-level
     * "supply changed" ping carrying no identity, which lets the anonymous browse page
     * refresh itself.
     */
    private void publishRealtime(Order savedOrder, List<Order> updatedOrders, List<Allocation> allocations) {
        events.publishToUser(savedOrder.getOwnerId(), RealtimeEventType.ORDER_SUBMITTED,
                savedOrder.getSide() + " order submitted for " + savedOrder.getQuantity() + " GPU(s)",
                savedOrder.getId().toString(),
                OrderResponse.from(savedOrder));

        for (Order order : updatedOrders) {
            if (order.getStatus() == OrderStatus.FILLED) {
                events.publishToUser(order.getOwnerId(), RealtimeEventType.ORDER_FILLED,
                        order.getSide() + " order fully filled",
                        order.getId().toString(),
                        OrderResponse.from(order));
            }
        }

        for (Allocation allocation : allocations) {
            // Both sides of the trade care about this one. The seller is resolved through
            // the SELL order because the allocation only stores sellOrderId.
            UUID sellerId = orderRepository.findById(allocation.getSellOrderId())
                    .map(Order::getOwnerId)
                    .orElse(null);

            events.publishToUsers(RealtimeEventType.ALLOCATION_CREATED,
                    "Allocation created for " + allocation.getQuantity() + " GPU(s)",
                    allocation.getId().toString(),
                    AllocationResponse.from(allocation),
                    allocation.getBuyerId(), sellerId);
        }

        // Identity-free: says only that the book moved, so the public listing page can refetch.
        events.publishMarket(RealtimeEventType.MARKET_UPDATED,
                "Marketplace supply changed", null, null);
    }

    /**
     * Cancels a resting order (B5). Owner-scoped: a missing order and someone else's order are
     * indistinguishable (404), matching {@link #findByIdForOwner}'s policy.
     *
     * <p>On a {@code TEMPLATE} series parent, cancels the whole series (D7/D6): every child
     * still {@code OPEN}/{@code PARTIALLY_FILLED} is cancelled the same way a single order
     * would be; children that already have allocations keep them — an allocation is a booked
     * trade with a counterparty who has already been charged, and unwinding it is the buyer's
     * right through {@link CancellationService}, not something a seller can do by deleting a
     * template.
     */
    @Transactional
    public OrderResponse cancelOrder(UUID id, UUID ownerId) {
        Order order = orderRepository.findById(id)
                .filter(o -> ownerId.equals(o.getOwnerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + id));

        if (order.getStatus() == OrderStatus.TEMPLATE) {
            return cancelSeries(order);
        }

        cancelOne(order);
        return OrderResponse.from(order);
    }

    private OrderResponse cancelSeries(Order parent) {
        if (parent.getStatus() == OrderStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This series is already cancelled");
        }
        for (Order child : orderRepository.findByParentOrderId(parent.getId())) {
            if (child.isMatchable()) {
                cancelOne(child);
            }
            // Children with an allocation are no longer isMatchable() (FILLED) or, if
            // partially filled, keep their existing allocation regardless — cancelOne only
            // ever stops FURTHER matching, it never touches an allocation row.
        }
        parent.setStatus(OrderStatus.CANCELLED);
        parent.setCancelledAt(Instant.now());
        return OrderResponse.from(parent);
    }

    private void cancelOne(Order order) {
        if (!order.isMatchable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order cannot be cancelled: currently " + order.getStatus());
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        metrics.incrementOrdersCancelled();

        // The book must not be touched ahead of the DB write it depends on — if this
        // transaction rolls back, the order was never actually cancelled.
        AfterCommit.run(() -> {
            matchingEngine.remove(order.getId());
            events.publishToUser(order.getOwnerId(), RealtimeEventType.ORDER_CANCELLED,
                    order.getSide() + " order cancelled", order.getId().toString(),
                    OrderResponse.from(order));
            events.publishMarket(RealtimeEventType.MARKET_UPDATED, "Marketplace supply changed", null, null);
        });
    }

    /**
     * Looks up one order, but only if it belongs to the caller. Returning empty for someone
     * else's order (rather than 403) keeps the endpoint from confirming that an id exists.
     */
    @Transactional(readOnly = true)
    public Optional<OrderResponse> findByIdForOwner(UUID id, UUID ownerId) {
        return orderRepository.findById(id)
                .filter(o -> ownerId.equals(o.getOwnerId()))
                .map(OrderResponse::from);
    }

    /** Every order this user has placed, newest activity first. */
    @Transactional(readOnly = true)
    public List<OrderResponse> findMine(UUID ownerId) {
        return orderRepository.findByOwnerId(ownerId, Sort.by(Sort.Direction.DESC, "priorityTimestamp"))
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    /** This user's orders on one side of the book — buyer view vs provider view. */
    @Transactional(readOnly = true)
    public List<OrderResponse> findMineBySide(UUID ownerId, OrderSide side) {
        return orderRepository.findByOwnerIdAndSide(ownerId, side,
                        Sort.by(Sort.Direction.DESC, "priorityTimestamp"))
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * The public marketplace: unfilled SELL capacity whose window has not closed, cheapest
     * first. Projected through {@link SupplyListingResponse} so no seller identity leaves
     * the service.
     */
    /**
     * @param viewerId when non-null, the caller's own listings are omitted. You cannot rent
     *                 your own GPUs — the engine refuses to match them — so surfacing them as
     *                 rentable would be an invitation to a dead end. Null for anonymous
     *                 visitors, who have no listings to hide.
     */
    @Transactional(readOnly = true)
    public List<SupplyListingResponse> findAvailableSupply(UUID viewerId) {
        return orderRepository.findAvailableSupply(Instant.now())
                .stream()
                .filter(o -> viewerId == null || !viewerId.equals(o.getOwnerId()))
                .map(SupplyListingResponse::from)
                .toList();
    }

    /**
     * Unfilled buy demand a provider could fill, best-paying first. Excludes the viewer's own
     * buy orders for the same self-trade reason as {@link #findAvailableSupply}.
     */
    @Transactional(readOnly = true)
    public List<DemandListingResponse> findOpenDemand(UUID viewerId) {
        return orderRepository.findOpenDemand(Instant.now(), viewerId)
                .stream()
                .map(DemandListingResponse::from)
                .toList();
    }

    /**
     * Fills an open buy request by placing a SELL that mirrors its terms.
     *
     * <p>The provider is quoting the buyer's own bid back at them, over the buyer's exact
     * window, so the resulting order is guaranteed price- and time-compatible and matches on
     * submission. This is the "allocate my GPUs to this request" action: the provider picks a
     * request rather than guessing at a price and hoping someone takes it.
     *
     * <p>The demand is re-read inside the transaction rather than trusted from the client, so
     * a stale page cannot fill a request at a price or quantity that no longer applies.
     *
     * <p>Deliberately bypasses {@link #validateWindow} (and so the D3 24-hour cap): this method
     * mirrors a window that was already accepted onto an existing {@code orders} row, not a
     * freshly chosen one. Routing it back through {@code placeOrder}'s validation would reject
     * filling any pre-existing demand whose window predates the cap — placement-time-only
     * validation must not become a retroactive one via this side door.
     */
    @Transactional
    public PlaceOrderResponse fillDemand(UUID buyOrderId, int gpus, UUID providerId) {
        Order demand = orderRepository.findById(buyOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Request not found: " + buyOrderId));

        if (demand.getSide() != OrderSide.BUY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a buy request");
        }
        if (providerId.equals(demand.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot fill your own request");
        }
        if (!demand.isMatchable() || demand.remainingQuantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This request has already been filled");
        }
        if (!demand.getWindow().getEnd().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This request's window has already passed");
        }

        int quantity = Math.min(gpus, demand.remainingQuantity());
        if (quantity < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Offer at least 1 GPU");
        }

        Order sell = Order.builder()
                .ownerId(providerId)
                .side(OrderSide.SELL)
                .pricePerGpuHour(demand.getPricePerGpuHour())
                .quantity(quantity)
                .window(new TimeWindow(demand.getWindow().getStart(), demand.getWindow().getEnd()))
                .priorityTimestamp(Instant.now())
                .build();
        Order savedSell = orderRepository.save(sell);
        return matchAndSettle(savedSell);
    }

    /**
     * Full order book across all users, including owner ids. Operator-facing only — no
     * product endpoint exposes this.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> findAll() {
        return orderRepository.findAll(Sort.by("priorityTimestamp"))
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findOpen() {
        return orderRepository
                .findByStatusIn(List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED),
                        Sort.by("priorityTimestamp"))
                .stream()
                .map(OrderResponse::from)
                .toList();
    }
}

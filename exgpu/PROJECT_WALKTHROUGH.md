# ExGPU — Project Walkthrough

A "follow the code" guide to the GPU Compute Exchange backend. Where
`EXGPU_APP_EXPLANATION.txt` is a folder/file map, this document walks the actual
runtime paths so you can read the codebase in the order the data flows.

Everything here reflects the code as it currently stands (after the billing
trust-model changes described in §4).

---

## 1. The one-paragraph mental model

ExGPU is a single Spring Boot app that runs a 3-dimensional order book. GPU
**providers** post `SELL` orders and **buyers** post `BUY` orders; an order is a
(price, quantity, **time window**) tuple. Two orders match only if side, price,
quantity, **and** time window are all compatible. A match produces an
`Allocation` — the record that buyer X may use seller Y's GPUs for a window at an
agreed price. Buyers prepay a `TokenBalance`. As compute is consumed, **usage
events** (over REST or Kafka) bill the buyer against that balance and write a
`UsageLedger` row. When the balance hits zero the response flags
`computeKilled`.

Three logical layers, one process:

| Layer | Entry points | Core class |
|-------|--------------|------------|
| Matching | `POST /orders` | `MatchingEngine` |
| Telemetry ingestion | `POST /usage-events`, Kafka `exgpu.usage-events` | `UsageEventConsumer` |
| Billing | both of the above | `BillingService` |

---

## 2. Flow A — placing an order and matching

**`OrderController.placeOrder` → `OrderService.placeOrder` → `MatchingEngine.submitOrder`**

1. **Validate.** `CreateOrderRequest` is bean-validated (side, price ≥ 0.0001,
   quantity ≥ 1, start/end present). `OrderService` additionally rejects
   `startTime >= endTime` with a 400.
2. **Build + persist.** An `Order` is built with status `OPEN` and a
   `priorityTimestamp` of now, then saved **first**. This matters: the saved
   instance is a *managed* JPA entity, so any later mutation the engine makes to
   it (filled quantity, status) is auto-flushed at commit — no second `save`.
3. **Match.** `MatchingEngine.submitOrder(savedOrder)` runs the match against the
   opposite book. The method is `synchronized`, so only one order matches at a
   time (simple and correct; not yet concurrent — see `TimeSliceLockManager`,
   which is an intentional empty placeholder for the future striped-lock design).
4. **Persist the results.** Counterpart orders in the engine's in-memory book
   came from *earlier* transactions and are therefore *detached*; `OrderService`
   explicitly `save`s each updated counterpart. New `Allocation` rows are
   inserted via `saveAll`. Metrics are bumped.
5. **Respond.** `PlaceOrderResponse` returns the order, the `MatchStatus`, the
   total matched quantity, and the allocations created.

### How the engine actually matches (`MatchingEngine.runMatch`)

The incoming order is the **taker**; resting orders in the opposite book are
**makers**. Candidates are filtered to those that are matchable, price-compatible
(`buy.price >= sell.price`), and whose window **overlaps** the incoming window,
then sorted **best price first, priority timestamp as tie-break**. For each
candidate until the incoming order is full:

- matched quantity = `min(remaining incoming, remaining candidate)`
- allocation window = the **intersection** of the two windows
- both orders' filled quantities and statuses are updated
- a fully filled candidate is removed from the book

Each `Allocation` is built to be a **self-contained billing record**:

```java
.buyOrderId(buyOrder.getId())
.sellOrderId(sellOrder.getId())
.buyerId(buyOrder.getOwnerId())              // who pays
.executionPrice(candidate.getPricePerGpuHour()) // maker price (price-time priority)
.quantity(matchedQty)
.window(matchedWindow)
```

Capturing `buyerId` and `executionPrice` here is what lets billing avoid trusting
the usage event later (§4). Execution price is the **resting/maker** order's
price — `candidate` is always the maker because it was already in the book when
the taker arrived.

**Resting behavior:** an order with no compatible counterpart returns `NO_MATCH`
but is still added to its own book, so a later opposite-side order can match it.
This is normal limit-order-book behavior and easy to miss.

---

## 3. Flow B — balances

**`BalanceController` → `BillingService.createOrTopUp` / `findBalance`**

- `POST /balances` creates a `TokenBalance` (PK = buyer id) or tops up an
  existing one. A null `ownerId` gets a random UUID.
- `TokenBalance` uses JPA optimistic locking (`@Version`). Concurrent conflicting
  updates surface as HTTP 409 via `GlobalExceptionHandler`
  (`ObjectOptimisticLockingFailureException`). There is no retry loop yet.
- `deduct`/`topUp`/`isExhausted` enforce non-negative balances in Java; the DB
  enforces `balance >= 0` as a backstop.

---

## 4. Flow C — usage billing (the trust-sensitive path)

A usage event answers one question: *how many seconds were used on this
allocation?* It is **not** trusted to say who pays or at what price.

### The contract

`SubmitUsageEventRequest` / Kafka `UsageEventMessage` both carry exactly:

```
eventId        // idempotency key
allocationId   // which allocation
usageSeconds   // wall-clock seconds used
```

`buyerId` and `pricePerGpuHour` were deliberately **removed** from this contract.
A producer can no longer bill a different buyer or override the agreed price.

> Naming note: the field is `usageSeconds`, not `gpuSeconds`. It is wall-clock
> seconds; the GPU count is applied separately via `allocation.quantity` in the
> cost formula. The old name implied "GPU-seconds" (count × time), which would
> have double-counted the GPU dimension.

### `BillingService.submitUsageEvent`

1. **Idempotency first.** Look up the ledger by `eventId`. If found, return the
   existing result with `duplicate = true` and **do not** deduct again. The DB's
   unique constraint on `idempotency_key` is the final guard.
2. Otherwise process the new event under a Micrometer timer.

### `BillingService.processNewEvent`

```
load allocation (404 if missing)
buyerId        = allocation.getBuyerId()        // derived, not from the event
executionPrice = allocation.getExecutionPrice() // derived, not from the event
  → 422 if either is null (legacy allocation with no billing data)

windowSeconds        = duration of allocation window
alreadyBilledSeconds = SUM(usage_seconds) for this allocation
  → 400 if alreadyBilledSeconds + usageSeconds > windowSeconds   (CUMULATIVE cap)

load balance for buyerId (404 if missing)
cost = (usageSeconds / 3600) * allocation.quantity * executionPrice   // scale 6
balance.deduct(cost)  → 422 on insufficient funds
save balance; write UsageLedger(idempotencyKey = eventId)
computeKilled = balance.isExhausted()
```

Two correctness properties worth calling out:

- **Window cap is cumulative, not per-event.** A single event can't exceed the
  window, and neither can the *sum* of all events for an allocation. The
  `sumUsageSecondsByAllocationId` query uses `COALESCE(SUM(...), 0)` so "nothing
  billed yet" reads as `0`. Because duplicates are filtered in step 1, a resent
  event never inflates the running total.
- **Price/payer are authoritative.** They come from the allocation that the
  matching engine wrote, so billing is independent of what the telemetry producer
  claims.

### Kafka path (`UsageEventConsumer.consume`)

Same `BillingService` call, different doorway:

- The consumer's value deserializer is an `ErrorHandlingDeserializer` wrapping a
  `JsonDeserializer`. A message that can't be parsed arrives as a **null value**
  rather than throwing — that's the signal for `DESERIALIZATION_FAILURE`, which
  is published to the DLQ (`exgpu.usage-events.dlq`).
- A valid message is mapped to `SubmitUsageEventRequest` and billed.
- `duplicate` results are logged, **not** sent to the DLQ.
- Any billing exception is caught and published to the DLQ with the event id and
  the original payload. Catching everything keeps one poison message from killing
  the listener — which makes DLQ monitoring operationally important.

---

## 5. Persistence model

Flyway owns the schema (`spring.jpa.hibernate.ddl-auto=none`). Migrations live in
`src/main/resources/db/migrations`:

- **`V1__init_schema.sql`** — `orders`, `allocations`, `token_balances`,
  `usage_ledger`, `audit_log` (the audit table is created but currently unused by
  code). Notable guards: `window_end > window_start`, `filled_quantity <=
  quantity`, unique `idempotency_key`, and a FK chain
  `usage_ledger → allocations → orders` plus `usage_ledger → token_balances`.
- **`V2__allocation_billing_fields_and_usage_rename.sql`** — adds
  `allocations.buyer_id` and `allocations.execution_price` (nullable for
  migration safety; always populated for new allocations), and renames
  `usage_ledger.gpu_seconds → usage_seconds` (a data-preserving `RENAME COLUMN`).

Entities (`domain/`) double as JPA mappings and lightweight domain objects.
`TimeWindow` is an `@Embeddable` value object shared by `Order` and `Allocation`.

---

## 6. Observability

`ExgpuMetrics` centralizes Micrometer counters/timer (orders submitted, matches,
allocations, usage events processed/duplicate/DLQ, billing deductions, kill
compute, billing latency timer). Actuator exposes `/actuator/prometheus`;
`prometheus/` scrapes it and `grafana/` provisions the datasource + the ExGPU
dashboard. `docker-compose.yml` brings up Postgres, Redis, Zookeeper, Kafka,
Prometheus, and Grafana for local runs.

---

## 7. Reading order for a newcomer

1. `domain/Order.java`, `domain/TimeWindow.java`, `domain/Allocation.java` — the
   nouns.
2. `engine/MatchingEngine.java` — the core algorithm.
3. `service/OrderService.java` — how matching is wrapped in a transaction and
   reconciled with JPA.
4. `service/BillingService.java` — idempotency, derivation, and the cumulative
   window cap.
5. `kafka/UsageEventConsumer.java` — the resilient ingestion doorway.
6. `db/migrations/*.sql` — the source of truth for the schema.

---

## 8. Known gaps / next steps

These are intentional limits of the current prototype, not bugs:

- The in-memory order book is **not** rebuilt from Postgres on restart, and
  multiple app instances would each keep their own book.
- `TimeWindow.overlaps` treats boundary-adjacent windows as overlapping (inclusive
  bounds); half-open semantics would be cleaner.
- `KillCompute` is a flag + metric only — no command is published and no
  allocation status transitions to `KILLED`.
- `synchronized` matching is the throughput ceiling until `TimeSliceLockManager`
  is implemented.
- Redis is in Compose and on the classpath but unused (auto-config excluded).
- Tests are fast unit/MVC-slice tests; there is no Testcontainers integration
  test exercising real Postgres/Flyway/Kafka together.

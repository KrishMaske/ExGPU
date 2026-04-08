-- Order lifecycle completion (B2/B4/B5) and recurring seller listings (A4).
--
-- Purely additive: no backfill, no rewrite of existing rows. Existing orders/allocations are
-- untouched; every new column is nullable or carries a default that matches "not part of a
-- series" / "never cancelled or expired yet".

-- Recurring series: parent template + concrete children (D6).
ALTER TABLE orders ADD COLUMN parent_order_id UUID;
ALTER TABLE orders ADD CONSTRAINT fk_orders_parent
    FOREIGN KEY (parent_order_id) REFERENCES orders(id) ON DELETE CASCADE;
CREATE INDEX idx_orders_parent ON orders(parent_order_id) WHERE parent_order_id IS NOT NULL;

ALTER TABLE orders ADD COLUMN recurrence_count INT;
ALTER TABLE orders ADD COLUMN recurrence_zone  VARCHAR(64);

-- recurrence_pattern (VARCHAR(20), added in V1) is kept as designed: the vocabulary is a
-- small, fixed enum, and the occurrence count / timezone live in typed sibling columns rather
-- than being packed into the string. See RecurrencePattern's Javadoc (D7).
ALTER TABLE orders ADD CONSTRAINT chk_recurrence_pattern
    CHECK (recurrence_pattern IS NULL OR recurrence_pattern IN ('DAILY','WEEKDAYS','WEEKLY'));
ALTER TABLE orders ADD CONSTRAINT chk_recurrence_shape CHECK (
    (recurring = FALSE AND recurrence_pattern IS NULL AND recurrence_count IS NULL)
 OR (recurring = TRUE  AND recurrence_pattern IS NOT NULL
                      AND recurrence_count BETWEEN 2 AND 60));

-- Lifecycle timestamps for the two states that were declared in OrderStatus but previously
-- unreachable: nothing ever cancelled a resting order (B5) or swept an expired one (B4).
ALTER TABLE orders ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN expired_at   TIMESTAMPTZ;

-- Backs the expiry sweep (OrderRepository.expirePastWindows) and the startup rehydration
-- query (OrderRepository.findByStatusIn), both of which filter on "still live" plus a window
-- boundary.
CREATE INDEX idx_orders_live_window_end ON orders(window_end)
    WHERE status IN ('OPEN','PARTIALLY_FILLED');

-- Notes:
--   * orders.status has no CHECK constraint (see V1), so the new TEMPLATE status (D6) needs
--     no DDL here at all.
--   * V1's chk_filled (filled_quantity <= quantity) and chk_window (window_end > window_start)
--     are satisfied by a TEMPLATE parent whose window is the series envelope.
--   * No orders.version column — see D10; the engine's counterparty writes go through a
--     conditional UPDATE (OrderRepository.applyFill), not optimistic locking.
--   * The 24-hour single-order window cap (D3) is placement-time application validation only,
--     not a DB constraint, so a pre-existing longer window is never rejected at startup.

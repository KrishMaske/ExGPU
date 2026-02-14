-- Shift billing from metered usage to the booked window, and add cancellation.
--
-- WHY: a buyer reserves a specific time window on specific GPUs. That capacity is withdrawn
-- from the market whether or not they run anything on it, so the provider has genuinely sold
-- those hours. Charging only for observed usage let a buyer hold capacity for free, and it
-- also made the UI ask the buyer to self-report usage — a control no real marketplace would
-- expose. The window is now the billable unit, charged once at booking.
--
-- Charging up front is also what makes refunds expressible: you cannot refund what was never
-- taken.

-- The ledger now records three kinds of movement rather than only metered usage.
--   BOOKING — full-window charge taken when an allocation is created (positive)
--   REFUND  — money returned on cancellation (negative)
--   USAGE   — telemetry-derived metering; retained for observability, no longer billed
ALTER TABLE usage_ledger ADD COLUMN charge_type VARCHAR(20) NOT NULL DEFAULT 'USAGE';

ALTER TABLE usage_ledger
    ADD CONSTRAINT chk_charge_type CHECK (charge_type IN ('BOOKING', 'USAGE', 'REFUND'));

-- token_cost was CHECK (> 0), which cannot express a refund. Widen it to "non-zero, and
-- negative only for refunds" so a sign error in a charge path fails loudly at the database
-- rather than quietly crediting someone.
ALTER TABLE usage_ledger DROP CONSTRAINT chk_token_cost;
ALTER TABLE usage_ledger ADD CONSTRAINT chk_token_cost CHECK (
    (charge_type = 'REFUND' AND token_cost < 0)
    OR (charge_type <> 'REFUND' AND token_cost >= 0)
);

-- usage_seconds was CHECK (> 0). A BOOKING row carries the window length, which is always
-- positive, but a REFUND row has no meaningful duration and records 0.
ALTER TABLE usage_ledger DROP CONSTRAINT chk_usage_seconds;
ALTER TABLE usage_ledger ADD CONSTRAINT chk_usage_seconds CHECK (usage_seconds >= 0);

-- Cancellation state on the allocation itself.
ALTER TABLE allocations ADD COLUMN cancelled_at    TIMESTAMPTZ;
ALTER TABLE allocations ADD COLUMN refunded_amount NUMERIC(18,6);

-- CANCELLED joins the existing ACTIVE/COMPLETED/KILLED states. There was no CHECK constraint
-- on this column, so nothing to widen — the enum lives in Java.

-- Partial index: the cancellation and refund views only ever look at cancelled rows, which
-- are the minority, so indexing the whole column would be mostly dead weight.
CREATE INDEX idx_allocations_cancelled ON allocations(cancelled_at)
    WHERE cancelled_at IS NOT NULL;

-- Existing allocations pre-date booking charges. Leaving them unbilled is deliberate: they
-- were already (or partly) paid for through metered usage under the old model, and
-- retroactively charging their full windows would take money for compute the buyer may never
-- have used and was never told they owed.

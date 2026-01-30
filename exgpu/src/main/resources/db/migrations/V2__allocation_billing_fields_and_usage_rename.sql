-- Make allocations a self-contained billing record so billing never has to trust the
-- price or payer reported on a usage event.
--
-- Columns are added nullable for migration safety on any pre-existing rows; the matching
-- engine always populates both for newly created allocations.
ALTER TABLE allocations ADD COLUMN buyer_id        UUID;
ALTER TABLE allocations ADD COLUMN execution_price NUMERIC(10,4);

ALTER TABLE allocations
    ADD CONSTRAINT chk_alloc_execution_price CHECK (execution_price IS NULL OR execution_price >= 0);

-- "gpu_seconds" was misleading: the value is wall-clock seconds of usage, and the GPU
-- count is applied separately via allocation.quantity in the cost formula. Rename to
-- usage_seconds. RENAME COLUMN preserves data and keeps existing constraints/indexes.
ALTER TABLE usage_ledger RENAME COLUMN gpu_seconds TO usage_seconds;
ALTER TABLE usage_ledger RENAME CONSTRAINT chk_gpu_seconds TO chk_usage_seconds;

-- Time-bounded access to matched compute.
--
-- An allocation says "buyer X may use N GPUs between T1 and T2". A lease is the operational
-- side of that: it tracks whether access is currently open, and it is the thing that gets
-- revoked when the window ends or the balance runs out.
--
-- Deliberately NOT stored here: the access credential itself. Credentials are minted on
-- demand as short-lived signed tokens and never persisted (see AccessCredentialMinter).
-- Only a fingerprint of the most recently issued token is kept, so an operator can
-- correlate an audit log entry to a lease without the database ever holding a usable key.
CREATE TABLE access_leases (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- One lease per allocation. UNIQUE is what makes lease creation idempotent: a retried
    -- or concurrent match cannot produce two leases for the same allocation.
    allocation_id           UUID NOT NULL UNIQUE,
    buyer_id                UUID NOT NULL,

    -- PENDING  — window has not started; access is not yet available
    -- ACTIVE   — inside the window; credentials may be minted
    -- EXPIRED  — window ended normally
    -- REVOKED  — access withdrawn early (balance exhausted / KillCompute)
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- Window copied from the allocation so lease transitions are a single-table scan and do
    -- not need a join on the scheduler's hot path.
    window_start            TIMESTAMPTZ NOT NULL,
    window_end              TIMESTAMPTZ NOT NULL,

    -- Which mock GPU node the buyer is pointed at. Assigned at creation so the value is
    -- stable across polls rather than being regenerated each request.
    node_ref                VARCHAR(100) NOT NULL,

    activated_at            TIMESTAMPTZ,
    ended_at                TIMESTAMPTZ,
    revoke_reason           VARCHAR(50),

    -- SHA-256 of the last credential handed out. Never the credential.
    last_credential_fingerprint VARCHAR(64),
    last_issued_at          TIMESTAMPTZ,

    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_lease_allocation FOREIGN KEY (allocation_id) REFERENCES allocations(id),
    CONSTRAINT chk_lease_status CHECK (status IN ('PENDING','ACTIVE','EXPIRED','REVOKED')),
    CONSTRAINT chk_lease_window CHECK (window_end > window_start)
);

-- The scheduler's two queries are "which PENDING leases should open?" and "which ACTIVE
-- leases should close?" — both filter on status plus a window boundary.
CREATE INDEX idx_leases_status_start ON access_leases(status, window_start);
CREATE INDEX idx_leases_status_end   ON access_leases(status, window_end);

-- Backs "show me my rentals' access state".
CREATE INDEX idx_leases_buyer ON access_leases(buyer_id);

-- Backfill leases for allocations that already exist, so rentals created before this
-- migration are not permanently stuck without an access path. Status is left at the
-- default PENDING; the scheduler moves each row to its correct state on its next tick,
-- which is exactly the same code path a new lease takes.
INSERT INTO access_leases (allocation_id, buyer_id, window_start, window_end, node_ref)
SELECT a.id,
       a.buyer_id,
       a.window_start,
       a.window_end,
       'gpu-node-' || substr(replace(a.id::text, '-', ''), 1, 6)
FROM allocations a
WHERE a.buyer_id IS NOT NULL
ON CONFLICT (allocation_id) DO NOTHING;

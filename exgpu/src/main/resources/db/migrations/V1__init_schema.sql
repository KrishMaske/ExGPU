CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ENUMS
CREATE TYPE order_side AS ENUM ('BUY', 'SELL');
CREATE TYPE order_status AS ENUM ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'EXPIRED', 'CANCELLED');
CREATE TYPE allocation_status AS ENUM ('ACTIVE', 'COMPLETED', 'KILLED');

-- ORDERS
CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id            UUID NOT NULL,
    side                order_side NOT NULL,
    status              order_status NOT NULL DEFAULT 'OPEN',
    price_per_gpu_hr    NUMERIC(10,4) NOT NULL,
    quantity            INT NOT NULL,
    filled_quantity     INT NOT NULL DEFAULT 0,
    window_start        TIMESTAMP NOT NULL,
    window_end          TIMESTAMP NOT NULL,
    recurring           BOOLEAN NOT NULL DEFAULT FALSE,
    recurrence_pattern  VARCHAR(20),
    priority_timestamp  TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_window CHECK (window_end > window_start),
    CONSTRAINT chk_quantity CHECK (quantity > 0),
    CONSTRAINT chk_price CHECK (price_per_gpu_hr >= 0),
    CONSTRAINT chk_filled CHECK (filled_quantity <= quantity)
);

CREATE INDEX idx_orders_matching ON orders(status, window_start, window_end);
CREATE INDEX idx_orders_owner ON orders(owner_id);

-- ALLOCATIONS
CREATE TABLE allocations (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    buy_order_id    UUID NOT NULL,
    sell_order_id   UUID NOT NULL,
    quantity        INT NOT NULL,
    window_start    TIMESTAMP NOT NULL,
    window_end      TIMESTAMP NOT NULL,
    status          allocation_status NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_alloc_window CHECK (window_end > window_start),
    CONSTRAINT chk_alloc_quantity CHECK (quantity > 0),
    CONSTRAINT fk_buy_order FOREIGN KEY (buy_order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_sell_order FOREIGN KEY (sell_order_id) REFERENCES orders(id) ON DELETE CASCADE
);

CREATE INDEX idx_allocations_buy_order ON allocations(buy_order_id);
CREATE INDEX idx_allocations_sell_order ON allocations(sell_order_id);

-- TOKEN BALANCES
CREATE TABLE token_balances (
    buyer_id        UUID PRIMARY KEY,
    balance         NUMERIC(18,6) NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_balance CHECK (balance >= 0)
);

-- USAGE LEDGER
CREATE TABLE usage_ledger (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    allocation_id       UUID NOT NULL,
    buyer_id            UUID NOT NULL,
    gpu_seconds         BIGINT NOT NULL,
    token_cost          NUMERIC(18,6) NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL UNIQUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_gpu_seconds CHECK (gpu_seconds > 0),
    CONSTRAINT chk_token_cost CHECK (token_cost > 0),
    CONSTRAINT fk_allocation FOREIGN KEY (allocation_id) REFERENCES allocations(id) ON DELETE CASCADE,
    CONSTRAINT fk_buyer FOREIGN KEY (buyer_id) REFERENCES token_balances(buyer_id)
);

CREATE INDEX idx_usage_ledger_allocation ON usage_ledger(allocation_id);
CREATE INDEX idx_usage_ledger_buyer ON usage_ledger(buyer_id);
CREATE INDEX idx_usage_ledger_idempotency ON usage_ledger(idempotency_key);

-- AUDIT LOG
CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    entity_type     VARCHAR(50) NOT NULL,
    entity_id       UUID NOT NULL,
    action          VARCHAR(50) NOT NULL,
    old_value       TEXT,
    new_value       TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
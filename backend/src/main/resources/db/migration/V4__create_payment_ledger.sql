-- Payment ledger (see docs/decisions/ADR-013-payment-ledger.md and docs/financial-policy-decisions.md).
--
-- Every row is an immutable financial event. Nothing here is ever updated or deleted by the application;
-- mistakes are corrected with new rows. Balances are calculated from these rows, never stored.
-- This migration only adds tables: no existing table or row is changed.

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    method VARCHAR(10) NOT NULL,
    reference VARCHAR(64),
    note VARCHAR(255),
    received_on DATE NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_payments_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_payments_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payments_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_payments_method CHECK (method IN ('CASH', 'UPI'))
);

CREATE INDEX idx_payments_customer_received ON payments (customer_id, received_on);

-- A payment is corrected by reversing it in full (with a reason); the correct payment is then recorded anew.
CREATE TABLE payment_reversals (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    reason VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    CONSTRAINT uk_payment_reversals_payment UNIQUE (payment_id),
    CONSTRAINT fk_payment_reversals_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_payment_reversals_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id)
);

-- Owner credits and extra charges. A bill-level row changes that bill's amount; an account-level row
-- (bill_id null) is a credit held for future bills. Account-level charges are not supported.
CREATE TABLE account_adjustments (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    bill_id UUID,
    direction VARCHAR(10) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    CONSTRAINT uk_account_adjustments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_account_adjustments_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_account_adjustments_bill FOREIGN KEY (bill_id) REFERENCES bills (id),
    CONSTRAINT fk_account_adjustments_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT ck_account_adjustments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_account_adjustments_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_account_adjustments_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_account_adjustments_scope CHECK (bill_id IS NOT NULL OR direction = 'CREDIT')
);

CREATE INDEX idx_account_adjustments_customer ON account_adjustments (customer_id);
CREATE INDEX idx_account_adjustments_bill ON account_adjustments (bill_id);

-- Money the farm gave back to a customer out of their credit.
CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    method VARCHAR(10) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    refunded_on DATE NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    CONSTRAINT uk_refunds_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_refunds_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_refunds_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT ck_refunds_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_refunds_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_refunds_method CHECK (method IN ('CASH', 'UPI'))
);

CREATE INDEX idx_refunds_customer ON refunds (customer_id);

-- How available credit was applied to issued bills (ALLOCATE) or taken back after a payment reversal (RELEASE).
-- trigger_type/trigger_id name the event that caused the row, for the audit trail.
CREATE TABLE bill_allocations (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    bill_id UUID NOT NULL,
    kind VARCHAR(10) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    trigger_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_bill_allocations_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_bill_allocations_bill FOREIGN KEY (bill_id) REFERENCES bills (id),
    CONSTRAINT ck_bill_allocations_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_bill_allocations_kind CHECK (kind IN ('ALLOCATE', 'RELEASE')),
    CONSTRAINT ck_bill_allocations_trigger CHECK (trigger_type IN ('PAYMENT', 'ADJUSTMENT', 'BILL_ISSUE', 'PAYMENT_REVERSAL'))
);

CREATE INDEX idx_bill_allocations_customer ON bill_allocations (customer_id);
CREATE INDEX idx_bill_allocations_bill ON bill_allocations (bill_id);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    phone VARCHAR(15) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_users_phone UNIQUE (phone)
);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(15),
    address VARCHAR(500),
    billing_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    join_date DATE NOT NULL,
    CONSTRAINT uk_customers_user_id UNIQUE (user_id),
    CONSTRAINT fk_customers_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE products (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    unit VARCHAR(20) NOT NULL,
    price NUMERIC(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_products_name UNIQUE (name)
);

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    product_id UUID NOT NULL,
    default_quantity NUMERIC(8, 3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_subscriptions_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_subscriptions_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_subscriptions_quantity_positive CHECK (default_quantity > 0),
    CONSTRAINT ck_subscriptions_dates_ordered CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_subscriptions_customer_status_dates ON subscriptions (customer_id, status, start_date, end_date);
CREATE INDEX idx_subscriptions_product_status_dates ON subscriptions (product_id, status, start_date, end_date);

CREATE TABLE daily_records (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    product_id UUID NOT NULL,
    subscription_id UUID,
    record_date DATE NOT NULL,
    slot VARCHAR(20) NOT NULL,
    quantity NUMERIC(8, 3) NOT NULL,
    unit_price_snapshot NUMERIC(12, 2),
    amount NUMERIC(18, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_daily_records_customer_product_date_slot UNIQUE (customer_id, product_id, record_date, slot),
    CONSTRAINT fk_daily_records_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_daily_records_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_daily_records_subscription FOREIGN KEY (subscription_id) REFERENCES subscriptions (id),
    CONSTRAINT ck_daily_records_quantity_nonnegative CHECK (quantity >= 0),
    CONSTRAINT ck_daily_records_amount_nonnegative CHECK (amount >= 0),
    CONSTRAINT ck_daily_records_price_nonnegative CHECK (unit_price_snapshot IS NULL OR unit_price_snapshot >= 0),
    CONSTRAINT ck_daily_records_slot_values CHECK (slot IN ('MORNING', 'EVENING')),
    CONSTRAINT ck_daily_records_status_values CHECK (status IN ('COLLECTED', 'SKIPPED', 'PAUSED', 'EXTRA')),
    CONSTRAINT ck_daily_records_status_values_consistent CHECK (
        (status IN ('COLLECTED', 'EXTRA') AND quantity > 0 AND unit_price_snapshot IS NOT NULL)
        OR
        (status IN ('SKIPPED', 'PAUSED') AND quantity = 0
            AND unit_price_snapshot IS NULL AND amount = 0)
    )
);

CREATE INDEX idx_daily_records_customer_date ON daily_records (customer_id, record_date);
CREATE INDEX idx_daily_records_product_date ON daily_records (product_id, record_date);

CREATE TABLE billing_periods (
    id UUID PRIMARY KEY,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    closed_by UUID,
    CONSTRAINT uk_billing_periods_period_start UNIQUE (period_start),
    CONSTRAINT fk_billing_periods_closed_by FOREIGN KEY (closed_by) REFERENCES users (id),
    CONSTRAINT ck_billing_periods_status_metadata CHECK (
        (status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL)
        OR
        (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL)
    )
);

CREATE TABLE bills (
    id UUID PRIMARY KEY,
    billing_period_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    customer_name_snapshot VARCHAR(100) NOT NULL,
    customer_phone_snapshot VARCHAR(15),
    customer_address_snapshot VARCHAR(500),
    total_amount NUMERIC(18, 2) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    issued_at TIMESTAMPTZ,
    issued_by UUID,
    CONSTRAINT uk_bills_period_customer UNIQUE (billing_period_id, customer_id),
    CONSTRAINT fk_bills_billing_period FOREIGN KEY (billing_period_id) REFERENCES billing_periods (id),
    CONSTRAINT fk_bills_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_bills_issued_by FOREIGN KEY (issued_by) REFERENCES users (id),
    CONSTRAINT ck_bills_status_issue_metadata CHECK (
        (status = 'DRAFT' AND issued_at IS NULL AND issued_by IS NULL)
        OR
        (status = 'ISSUED' AND issued_at IS NOT NULL AND issued_by IS NOT NULL)
    ),
    CONSTRAINT ck_bills_total_positive CHECK (total_amount > 0)
);

CREATE INDEX idx_bills_customer_period ON bills (customer_id, period_start);

CREATE TABLE bill_lines (
    id UUID PRIMARY KEY,
    bill_id UUID NOT NULL,
    daily_record_id UUID NOT NULL,
    record_date DATE NOT NULL,
    slot VARCHAR(20) NOT NULL,
    description VARCHAR(150) NOT NULL,
    quantity NUMERIC(8, 3) NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    CONSTRAINT uk_bill_lines_daily_record UNIQUE (daily_record_id),
    CONSTRAINT fk_bill_lines_bill FOREIGN KEY (bill_id) REFERENCES bills (id),
    CONSTRAINT fk_bill_lines_daily_record FOREIGN KEY (daily_record_id) REFERENCES daily_records (id),
    CONSTRAINT ck_bill_lines_values CHECK (quantity > 0 AND unit_price >= 0 AND amount >= 0)
);

CREATE INDEX idx_bill_lines_bill ON bill_lines (bill_id);

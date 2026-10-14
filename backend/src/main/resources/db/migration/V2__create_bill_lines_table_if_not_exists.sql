CREATE TABLE IF NOT EXISTS bill_lines (
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

CREATE INDEX IF NOT EXISTS idx_bill_lines_bill ON bill_lines (bill_id);

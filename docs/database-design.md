# Database Design (Current)

This document describes the database design based on the current implementation. It records only entities that are implemented in code. Planned entities are listed at the end and explicitly marked.

Implemented Entities

User
- Table: users
- Primary key: id (UUID)
- Fields:
  - id: UUID (generated)
  - phone: String (unique, length 10) — login identifier
  - password_hash: String (BCrypt hashed password)
  - role: enum (CUSTOMER, OWNER) persisted as STRING
  - created_at: Instant
- Constraints:
  - unique constraint on phone (uk_users_phone)
- Notes:
  - Passwords are never stored plaintext. The hashing is done using BCrypt.

Customer
- Table: customers
- Primary key: id (UUID)
- Fields:
  - id: UUID (generated)
  - user_id: foreign key -> users.id (ManyToOne, fetch=LAZY, not nullable)
  - name: String
  - phone: String (redundant local copy for convenience; authoritative login phone is in User.phone)
  - address: String
  - billing_type: enum (PREPAID, MONTHLY) persisted as STRING
  - status: enum (ACTIVE, PAUSED, INACTIVE) persisted as STRING
  - join_date: LocalDate
- Constraints:
  - unique constraint on user_id (uk_customers_user_id)
- Notes:
  - Customer is separate from User: authentication data (User) and business data (Customer) are kept separate.

Product
- Table: products
- Primary key: id (UUID, generated)
- Fields:
  - id: UUID
  - name: String (required, maximum length 100, unique)
  - description: String (optional, maximum length 500)
  - unit: ProductUnit enum (LITRE, KILOGRAM), persisted as STRING
  - price: BigDecimal (required, NUMERIC(12,2)); current catalog price
  - status: ProductStatus enum (ACTIVE, INACTIVE), persisted as STRING; defaults to ACTIVE
  - created_at: Instant (server-generated, immutable after creation)
  - updated_at: Instant (server-managed on update)
- Constraints:
  - unique constraint on name (uk_products_name)
- Notes:
  - Product has no JPA relationships to future modules yet.
  - Zero price is permitted; negative price and values exceeding NUMERIC(12,2) precision/scale are rejected by request validation.
  - Product.price is not a historical transaction price.

Subscription
- Table: subscriptions
- Primary key: id (UUID, generated)
- Fields:
  - customer_id: required foreign key -> customers.id (`ManyToOne`, LAZY)
  - product_id: required foreign key -> products.id (`ManyToOne`, LAZY)
  - default_quantity: required `BigDecimal`, `NUMERIC(8,3)`, strictly positive
  - status: `SubscriptionStatus` enum (ACTIVE, PAUSED, CANCELLED), persisted as STRING; defaults to ACTIVE
  - start_date: required `LocalDate`
  - end_date: nullable inclusive `LocalDate`; null means ongoing
  - created_at: server-generated `Instant`
  - updated_at: server-managed `Instant`
- Constraints and indexes:
  - Check constraints require positive quantity and `end_date` absent or on/after `start_date`.
  - Indexes support customer/status/date and product/status/date filtering.
  - No unique `(customer_id, product_id)` constraint: successive historical periods are valid.
  - No JPA collections are added to Customer or Product; relationships are owned by Subscription and do not cascade.
- Business rules:
  - Application-level checks prevent overlapping ACTIVE periods for the same Customer/Product using inclusive dates.
  - PAUSED and CANCELLED historical periods do not block a new ACTIVE period.
  - **PLANNED / NOT YET IMPLEMENTED:** database-level concurrent-write enforcement for PostgreSQL. H2 tests do not establish PostgreSQL race safety.
  - Subscription stores neither Product name/unit nor price. `default_quantity` is only the recurring default; actual supply and historical price belong to DailyRecord.

DailyRecord
- Table: daily_records
- Primary key: id (UUID, generated)
- Fields:
  - customer_id: required FK -> customers.id (`ManyToOne`, LAZY)
  - product_id: required FK -> products.id (`ManyToOne`, LAZY)
  - subscription_id: optional FK -> subscriptions.id (`ManyToOne`, LAZY)
  - record_date: required `LocalDate`
  - slot: required `DailyRecordSlot` (MORNING, EVENING), persisted as STRING
  - quantity: required `BigDecimal NUMERIC(8,3)`
  - unit_price_snapshot: nullable `BigDecimal NUMERIC(12,2)`; required for COLLECTED/EXTRA
  - amount: required server-calculated `BigDecimal NUMERIC(18,2)`
  - status: required `DailyRecordStatus` (COLLECTED, SKIPPED, PAUSED, EXTRA), persisted as STRING
  - created_at / updated_at: required server-managed `Instant`
- Constraints and indexes:
  - Unique constraint `uk_daily_records_customer_product_date_slot` across customer_id, product_id, record_date, slot.
  - Check constraints require nonnegative quantity/amount/price and consistent status, quantity, snapshot, and amount values.
  - Customer/date and product/date indexes support history and billing-period queries.
  - No relationship cascades and no inverse collections on Customer, Product, or Subscription.
- Business rules:
  - DailyRecord is actual per-date/slot outcome, not Subscription's recurring default.
  - SKIPPED/PAUSED have zero quantity/amount and no price snapshot; COLLECTED/EXTRA have positive quantity and a price snapshot.
  - Amount is calculated with `BigDecimal`, rounded HALF_UP once to scale 2, and persisted. Product.price changes do not alter historical values.
  - A Subscription link is optional and is validated for matching Customer/Product/date range, without requiring its current status to be ACTIVE.
  - Past records can be created with an explicit historical snapshot but cannot be updated. Only today's records permit correction.
  - Duplicate checks run in the service and the database unique constraint remains authoritative. H2 does not prove PostgreSQL concurrent-write safety.

BillingPeriod
- Table: billing_periods
- Primary key: id (UUID, generated)
- Fields:
  - period_start / period_end: required canonical inclusive `LocalDate` month boundaries
  - status: `BillingPeriodStatus` (OPEN, CLOSED), persisted as STRING
  - created_at: required server-managed `Instant`
  - closed_at: nullable server-managed `Instant`
  - closed_by: nullable lazy FK -> users.id; populated on close
- Constraints and indexes:
  - Named unique constraint `uk_billing_periods_period_start` prevents duplicate period rows.
  - A database check keeps CLOSED status and closure metadata consistent.
  - Application validation restricts period identifiers to valid `YYYY-MM` calendar months and canonical first/last dates.
- Business rules:
  - Periods begin OPEN; close is explicit, irreversible in v1, and idempotent to repeat.
  - Only completed calendar months may be closed. The business date uses the explicit `Asia/Kolkata` zone rather than the host machine default; current/future periods remain inspectable.
  - GET inspection does not create a missing row; a missing period is reported as OPEN with virtual canonical boundaries.
  - DailyRecord create/update and period close coordinate on a pessimistically locked BillingPeriod row. DailyRecord current/past date rules use the same `Asia/Kolkata` business clock.
  - Period initialization uses a separate transaction; the named unique key resolves concurrent first-time creation before callers lock the row.
  - Close calculates chargeable record count and amount while holding the period row lock. Only COLLECTED/EXTRA count; an empty period may close.
  - The summary is informational and does not prove physical collection completeness.
  - No Bill is created by close. A closed period blocks DailyRecord insert/update; no Bill, Payment, or BalanceAdjustment is implemented in this milestone.

Enums
- BillingType: PREPAID, MONTHLY
- CustomerStatus: ACTIVE, PAUSED, INACTIVE
- ProductUnit: LITRE, KILOGRAM
- ProductStatus: ACTIVE, INACTIVE
- SubscriptionStatus: ACTIVE, PAUSED, CANCELLED
- DailyRecordSlot: MORNING, EVENING
- DailyRecordStatus: COLLECTED, SKIPPED, PAUSED, EXTRA

Databases
- Tests / development: H2 (in-memory) — configured in application-dev.properties
- Production: PostgreSQL (dependency declared in pom.xml)

Planned / Not Yet Implemented Entities (from AGENTS.md / domain model)
- Bill
- Payment
- BalanceAdjustment

Bill, Payment, and BalanceAdjustment are explicitly NOT yet present in the schema and are listed here so future design can reference them.

Important business/data-design rule
- **IMPLEMENTED:** DailyRecord snapshots unit_price at the time of the recorded supply and stores its calculated amount. Future Bills must not recalculate historical records from current Product.price.
- **IMPLEMENTED:** BillingPeriod provides calendar-month OPEN/CLOSED state and a protected reconciliation summary.
- **PLANNED / NOT YET IMPLEMENTED:** Bill generation from closed periods, Payment, and BalanceAdjustment.

Product price/currency note
- **INFERENCE:** The initial business is assumed to use one currency (likely INR based on project examples). No currency field or currency configuration is implemented. Revisit before supporting multiple currencies.

Indexes and performance
- Current model includes unique indexes/constraints for user phone, customer user_id, product name, and DailyRecord customer/product/date/slot.
- DailyRecord also has customer/date and product/date indexes for history queries. Add further indexes only when query patterns and plans justify them.

Foreign keys and transactional integrity
- Services should use transactions to maintain referential integrity (e.g., creating User + Customer atomically).

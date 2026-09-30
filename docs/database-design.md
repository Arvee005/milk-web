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
  - Subscription stores neither Product name/unit nor price. `default_quantity` is only the recurring default; actual supply and historical price belong to future DailyRecord.

Enums
- BillingType: PREPAID, MONTHLY
- CustomerStatus: ACTIVE, PAUSED, INACTIVE
- ProductUnit: LITRE, KILOGRAM
- ProductStatus: ACTIVE, INACTIVE
- SubscriptionStatus: ACTIVE, PAUSED, CANCELLED

Databases
- Tests / development: H2 (in-memory) — configured in application-dev.properties
- Production: PostgreSQL (dependency declared in pom.xml)

Planned / Not Yet Implemented Entities (from AGENTS.md / domain model)
- DailyRecord
- Bill
- Payment
- BalanceAdjustment

These are described in AGENTS.md and will be implemented later. They are explicitly NOT yet present in the schema and are listed here so future migrations and design can reference them.

Important business/data-design rule (planned)
- DailyRecord must snapshot unit_price at the time of transaction; historical bills must not re-calc using current Product.price. This rule is recorded in AGENTS.md and must be followed when implementing daily records and billing. **PLANNED / NOT YET IMPLEMENTED.**

Product price/currency note
- **INFERENCE:** The initial business is assumed to use one currency (likely INR based on project examples). No currency field or currency configuration is implemented. Revisit before supporting multiple currencies.

Indexes and performance
- Current model includes unique indexes (phone, user_id). Additional indexes (customer, product, date) should be added when queries require them (AGENTS.md guidance).

Foreign keys and transactional integrity
- Services should use transactions to maintain referential integrity (e.g., creating User + Customer atomically).

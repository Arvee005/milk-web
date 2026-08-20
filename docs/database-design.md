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

Enums
- BillingType: PREPAID, MONTHLY
- CustomerStatus: ACTIVE, PAUSED, INACTIVE

Databases
- Tests / development: H2 (in-memory) — configured in application-dev.properties
- Production: PostgreSQL (dependency declared in pom.xml)

Planned / Not Yet Implemented Entities (from AGENTS.md / domain model)
- Product
- Subscription
- DailyRecord
- Bill
- Payment
- BalanceAdjustment

These are described in AGENTS.md and will be implemented later. They are explicitly NOT yet present in the schema and are listed here so future migrations and design can reference them.

Important business/data-design rule (planned)
- DailyRecord must snapshot unit_price at the time of transaction; historical bills must not re-calc using current Product price. This rule is recorded in AGENTS.md and should be followed when implementing billing and daily records.

Indexes and performance
- Current model includes unique indexes (phone, user_id). Additional indexes (customer, product, date) should be added when queries require them (AGENTS.md guidance).

Foreign keys and transactional integrity
- Services should use transactions to maintain referential integrity (e.g., creating User + Customer atomically).

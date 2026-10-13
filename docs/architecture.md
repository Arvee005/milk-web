# Architecture

This document describes the high-level architecture of Milk Web and the responsibilities of each layer.

Overview

Frontend (React + TypeScript + Vite)
- Purpose: user interface for customers and owners.
- Located in: frontend/
- Rationale: fast development with TypeScript safety and Vite for a modern dev server and build pipeline. (See ADR-008)

REST API (Spring Boot)
- Purpose: backend HTTP API consumed by frontend and future mobile clients.
- Located in: backend/src/main/java/com/radhikafarms/milkweb
- Rationale: Spring Boot provides mature infrastructure for REST, security, data access, and testing.

Security / JWT
- JWT tokens are used to authenticate API requests. JwtAuthenticationFilter validates the token and sets the SecurityContext.
- Passwords are hashed using BCrypt and stored in the User entity.
- The React owner and customer workspaces authenticate through the same API: they post phone/password to `/api/auth/login`, hold the access token in tab-scoped `sessionStorage`, and validate both new and restored sessions with `/api/auth/me` before selecting a workspace.
- Authentication states are explicit (`checking`, `authenticated`, `anonymous`, and session-check `error`). Workspace selection uses only the role returned by `/api/auth/me`; owner billing and customer content remain hidden during validation. Backend authorization remains authoritative.
- A shared TypeScript auth API module owns login/session response validation, bearer headers, and user-facing 401/403 errors. It has no runtime dependency beyond the browser platform.
- The customer workspace loads `/api/customer/me` and uses CUSTOMER-only read endpoints for daily records, subscriptions, and issued bills. Each customer data query is scoped by the authenticated User-to-Customer relationship and is not fetched through owner endpoints. Payment history and balance APIs remain unavailable.

Controller layer
- Purpose: HTTP endpoints, request/response mapping, validation, DTO translation.
- Controllers do not contain business logic.

Service layer
- Purpose: business logic, transactional boundaries, orchestration of repositories and other services.
- Examples: UserService, CustomerService (creates User + Customer transactionally).

Repository layer
- Purpose: data access using Spring Data JPA.
- Repositories are interfaces (JpaRepository) and used by services.

JPA / Hibernate
- Entities annotated with @Entity. UUID primary keys are used.
- H2 in-memory with Hibernate `create-drop` is configured for development/test profiles. PostgreSQL JDBC is a runtime dependency and PostgreSQL is the intended production database, but no PostgreSQL datasource/profile or migration tool is configured in the repository.
- The current schema is inferred from eight JPA entity mappings, not captured production DDL. Hibernate-generated DDL is not a production migration history.

## Database Lifecycle Direction (PROPOSED; NOT IMPLEMENTED)

Recommend Flyway with reviewed PostgreSQL SQL migrations as the single production schema authority and Hibernate `validate` for managed PostgreSQL profiles. Keep `create-drop` only for disposable tests. Create V1 for a verified empty schema; for any existing populated database, inspect and reconcile it to V1 before explicit baseline. Automatic baselining and assumptions that production is empty are prohibited by this proposal.

The database URL/credentials, explicit production profile, migration runner, backup/restore operations, and PostgreSQL validation are not yet configured or verified. See [Database Migration Strategy](./database-migration-strategy.md) and proposed [ADR-011](./decisions/ADR-011-flyway-postgresql-migrations.md). Production use is blocked until target databases are inventoried, the combined uncommitted work is reviewed/checkpointed, and operational ownership is established.

Why separate layers?
- Separation of concerns: makes code easier to test, reason about, and change.
- Controllers handle HTTP and validation; services handle business rules; repositories handle persistence.

How this supports mobile apps
- Stateless JWT-based authentication allows mobile clients to authenticate once and use the token on subsequent requests.
- Resource-oriented REST endpoints (not coupled to React) are suitable for mobile APIs.

Current vs future
- Implemented backend modules include authentication, users, customers, products, owner-managed subscriptions, owner-managed DailyRecords, BillingPeriod reconciliation/closing, and Bill generation/issuance. Customer-scoped read APIs also expose profile, records, subscriptions, and ISSUED bills.
- Subscription supplies a recurring default; DailyRecord records the actual outcome for a customer/product/date/collection slot. A closed BillingPeriod prevents later DailyRecord writes and provides an informational reconciliation summary. Bill generation snapshots stored chargeable records into a DRAFT; a separate owner action issues the Bill. There is no payment ledger, allocation, adjustment, currency snapshot, or reliable balance.
- Implemented frontend workspaces include owner billing, owner DailyRecord entry/review, owner customer setup, owner product setup, and customer profile, Overview/Records, subscriptions, and issued bills. Customer payment and balance views remain unimplemented.
- The owner workspace is a single React page with four sections (Billing, Daily records, Customers, Products) selected by local state; there is no client-side router or state-management library. The DailyRecord entry form submits intent only: quantity/price/status rules, amount calculation, price-snapshot immutability, and BillingPeriod state are all enforced by the backend. Customer and Product display labels are resolved in the browser from the existing OWNER reference lists because `DailyRecordResponse` deliberately exposes only identifiers.
- The owner Customers and Products sections follow the same shape. Customer and product create/update reuse the existing `POST`/`PUT` endpoints unchanged; customer and product lists load fully and are filtered in the browser, because the list endpoints expose no query parameters. Customer `PUT` is treated as a full replacement, so every update path — the edit form and the list-level status action — assembles the complete body. Product creation omits `status` because the server owns the initial value; product `PUT` always includes it. No backend module, entity, DTO, or schema was changed for this slice.

## Financial Domain Direction (PROPOSED; DOCUMENTATION ONLY)

The proposed next financial architecture is an account-level source ledger: immutable OWNER-attributed receipts, separate explicit allocation events to same-customer ISSUED bills, and separate reasoned debit/credit adjustments. Bill balances and customer due/credit are derived views; mutable balance fields are not authoritative. This supports bill-centric collection flows while representing advance/excess receipts as unallocated account credit.

This direction is not implemented or yet approved as business policy. Currency (including existing Bill interpretation), automatic allocation, PREPAID semantics, corrections/refunds, and bill void/reissue remain owner decisions. No payment entities, migrations, APIs, tests, or UI are present. See [Payment and Balance Domain Design](./payment-domain-design.md) and [ADR-010](./decisions/ADR-010-customer-financial-ledger.md).

Read more: docs/decisions/ADR-008-react-vite-frontend.md, docs/decisions/ADR-001-jwt-authentication.md, docs/decisions/ADR-010-customer-financial-ledger.md
Authentication flow details and alternatives: docs/decisions/ADR-009-frontend-authentication-flow.md

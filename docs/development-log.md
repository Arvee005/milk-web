# Development Log

This development log is a chronological record of meaningful milestones in the Milk Web project. Each entry is based on Git history and verified repository contents. Facts are drawn from commit metadata and code; where reasons are not recorded, they are marked as INFERENCE.

# Production Database and Migration Strategy (DOCUMENTATION ONLY)

## Verified repository state
- The backend uses Spring Boot 4.1.0, Java 21, Spring Data JPA/Hibernate, with H2 and PostgreSQL JDBC runtime dependencies.
- Development (`application-dev.properties`) and tests (`src/test/resources/application.properties`) explicitly use in-memory H2 and `spring.jpa.hibernate.ddl-auto=create-drop`.
- Base application properties do not configure PostgreSQL URL/credentials, a production profile, connection pool settings, or a migration tool. No SQL scripts, Flyway/Liquibase setup, schema snapshot, or database deployment service was found.
- Eight current entity tables are represented by JPA mappings. Their mappings and database documentation are not proof of any live PostgreSQL schema.
- `User.phone` is mapped at width 15 while API validation accepts ten-digit input; prior database-design text describing a width of 10 was corrected.

## Recommendation and prerequisites
- Added a Flyway/PostgreSQL migration strategy recommendation: versioned PostgreSQL SQL, Hibernate `validate`, a complete V1 for fresh databases, and explicit baseline only after an existing database is inspected and reconciled to V1.
- Added proposed ADR-011 for Flyway and the conditional baseline approach. No tool was installed/configured; no database was connected, created, baselined, or changed.
- Documented local/CI/staging/production lifecycle, expand-and-contract changes, backups and restore drills, H2/PostgreSQL differences, financial-schema dependencies, and migration test/release gates.
- Before implementation, inspect every target database; establish production ownership/configuration/backups; resolve schema drift and canonical V1; confirm Flyway compatibility; obtain financial policy approvals; and review/checkpoint the existing combined uncommitted worktree.

## Verification
- Documentation-only changes. `git diff --check` passed.
- No application tests, migrations, PostgreSQL validation, baseline, production connection, or restore test was run or claimed.
- Existing uncommitted application and documentation work was preserved; no commit was created.

# Payment and Balance Domain Design (DOCUMENTATION ONLY)

The owner-facing options, recommended defaults, dependencies, deferrals, and approval checklist are in [Financial Policy Decisions](./financial-policy-decisions.md). All recommendations remain pending owner approval.

## Existing behavior confirmed
- Monthly bills are generated only for MONTHLY customers from chargeable DailyRecords in a CLOSED period. Generated bills are DRAFT; an OWNER explicitly issues them, recording `issuedAt` and `issuedBy`.
- The CUSTOMER bill endpoint returns only ISSUED bills. Issued status is not payment status.
- `PREPAID` is currently only a billing-type value that excludes a customer from monthly bill generation. There is no payment, allocation, adjustment, refund, or balance model.
- Bill, DailyRecord, and line-item monetary values use decimal columns, but currency is not persisted. INR remains unconfirmed. No Flyway/Liquibase dependency or migration scripts were found; development uses H2 `create-drop` and PostgreSQL is the intended production database.

## Design recommendation
- Proposed an account-level financial ledger with separately attributed, append-only receipts, bill allocation events, and non-cash balance adjustments. Derive bill and account balances; treat stored summaries only as rebuildable projections if later needed.
- Recommend explicit bill allocations, with unapplied receipts retained as unallocated customer credit. A bill-centric owner shortcut can still create a receipt and allocation. Do not silently distribute a payment across bills.
- Added proposed OWNER/CUSTOMER API contracts, financial invariants, currency and precision options, idempotency/locking guidance, audit requirements, and a synthetic test matrix.
- Added ADR-010 as Proposed. It recommends a minimal immutable financial source model rather than mutable authoritative balance fields or a full double-entry accounting system.

## Decisions still required
- Confirm operating currency and how existing currency-less issued Bills should be interpreted.
- Confirm account-level credit and PREPAID behavior; allocation order; receipt correction vs refund; issued-bill correction/void/reissue; and adjustment scope/approval.
- Choose a production schema migration strategy before any financial persistence changes.

## Verification and scope
- Documentation only: no application code, schema, migrations, tests, or endpoints were changed or added for this design.
- No test suite was run for this documentation-only phase. Prior Phase 4 verification remains recorded below; this entry does not claim new test results.
- `git diff --check` is run after these documentation changes. Existing uncommitted application and Phase 1-4 work is preserved; nothing is committed.

# Customer Bills (IMPLEMENTED)

## Existing Billing Lifecycle
- A billing period must be CLOSED before draft bill generation. Generation uses only chargeable DailyRecords for a MONTHLY customer and snapshots record descriptions, quantities, unit prices, and amounts into immutable bill lines.
- Generated bills begin as DRAFT. The owner explicitly issues a draft; that operation changes status to ISSUED and stores both `issuedAt` and `issuedBy`. The Bill database check constraint requires these issue fields exactly for ISSUED bills.
- The customer view therefore exposes only ISSUED bills with issue timestamps. Closed periods and DRAFT bills are not treated as customer-visible/finalized.
- No payment ledger exists; ISSUED is not represented as paid/unpaid.

## What Was Implemented
- Added CUSTOMER-only `GET /api/customer/bills` with bounded pagination (page default 0, size default 20 and maximum 100), stable created-time/ID ordering, and an explicit response page DTO.
- Resolved customer ownership from the authenticated User ID; rejected supplied `customerId`; filtered visible bills in the repository by that customer, ISSUED status, and issue timestamp.
- Returned customer-safe bill and line DTOs with billing period, issue time, total, and immutable bill-line summaries. Customer PII snapshots, issuer identity, line IDs, and DailyRecord IDs are omitted.
- Added a responsive Bills view with expandable item details, loading/empty/error/retry states, and pagination. It explicitly states that an issued bill is not a payment receipt and that payment status and currency are unavailable; amounts are shown as stored numeric values without a currency symbol.

## Architectural Decisions and Trade-offs
- Preserved owner bill generation, retrieval, and issue workflows. Customer visibility reuses the existing ISSUED lifecycle and does not introduce a new bill state.
- Page bill rows first, then batch-fetch line collections only for the page's customer-scoped IDs. This avoids pagination over a collection fetch join and avoids an N+1 query.
- A bill is listed, not separately retrieved by client-provided ID; this avoids an unnecessary endpoint and an independent ID-authorization path.

## Verification
- Backend `BillControllerTest`: 4 tests passed. The full backend suite passed: 138 tests, 0 failures/errors/skips (`cd backend && ./mvnw clean test`).
- Frontend tests: 34 passed; production build, lint, and `git diff --check` passed. API/view-model coverage includes paging, period/amount/status data, loading/empty/error states, retry behavior, response validation, authorization/network failures, and absence of payment status.
- Tests use only synthetic H2 data. No live browser-to-backend bill verification is claimed.

## Remaining Work
- Payments, refunds, balance adjustments, and outstanding balances remain unimplemented. A separate milestone must define payment allocation before displaying payment status or balances.

# Customer Subscriptions (IMPLEMENTED)

## What Was Implemented
- Added CUSTOMER-only `GET /api/customer/subscriptions`. It derives the Customer from the authenticated User ID, reads only that customer's subscriptions, and rejects a supplied `customerId`.
- Added a customer-facing DTO with product name/unit, default quantity, status, inclusive start/end dates, `periodState`, and `effectiveToday`. Product is fetched through an entity graph; no JPA relationships or Customer/Product IDs are returned.
- `periodState` is date-only (UPCOMING, IN_PERIOD, ENDED) relative to the configured India business date. `effectiveToday` requires both ACTIVE status and an inclusive date range containing that date.
- Replaced the subscriptions placeholder with responsive current/other groups, readable status/date labels, loading/empty/error/retry states, and a note that defaults are plans rather than evidence of actual milk collected.

## Architectural Decisions and Trade-offs
- Reused the existing customer lookup and subscription domain/query, without changing owner subscription endpoints, mutation rules, or SecurityConfig.
- Returned all customer subscriptions in a list, matching the existing owner list pattern; the current domain is expected to have modest per-customer volume. Pagination can be added if real usage demonstrates the need.
- Preserved status and date semantics independently. A PAUSED/CANCELLED row may still be within its date range, and an ACTIVE row can be upcoming or ended; neither alone is represented as effective today.
- Used the existing `Clock` configured for Asia/Kolkata so the derived date state follows the established business date.

## Verification
- Focused backend `SubscriptionControllerTest`: 11 tests passed, including isolated customer data, cross-customer isolation, status/date disagreement, inclusive today boundaries, supplied customerId rejection, OWNER denial, and unauthenticated rejection.
- Full backend suite: 136 tests passed, 0 failures/errors/skips (`cd backend && ./mvnw clean test`).
- Frontend tests: 29 passed; production build, lint, and `git diff --check` passed.
- Existing DailyRecord test fixtures that asserted "today" were aligned with the configured India business `Clock`; this avoids dependence on the machine's local timezone.
- Tests use synthetic data in the existing H2 test environment. No live browser-to-backend subscription verification was performed.

## Remaining Work
- Customer-facing bills, payment history, and balance are not implemented. Subscription changes remain owner-managed only.

# Customer Daily Records and Today's Collection (IMPLEMENTED)

## What Was Implemented
- Added `GET /api/customer/daily-records` for customer-owned record history. It supports a date or inclusive date range, optional collection slot/status, and bounded pagination.
- Resolved the Customer using the authenticated User ID and constrained the repository query by that Customer ID. A supplied `customerId` query parameter is rejected. The paged query fetches Product for product name/unit fields in the customer response DTO.
- Added customer Overview cards for today's actual MORNING and EVENING records, including product, quantity, status, historical unit-price snapshot, and amount where chargeable. The India-local day is loaded on entry and refreshed every 60 seconds while Overview is active, with a manual refresh control. An absent slot is shown as “No record available for this slot,” not as SKIPPED.
- Added a Records page with inclusive date-range, slot, status, paging, loading, empty, error, and retry UI. Existing owner record management was not changed.

## Architectural Decisions and Trade-offs
- Reused `DailyRecordService` and repository query patterns while adding a separate mandatory-customer query. The owner query remains unchanged and owner-only.
- Capped a date range at 366 calendar dates and pages at 100 records; defaults are page 0 and size 50. Frontend currently uses a 100-row bounded request for today's overview and 20-row pages for history.
- Wrapped the page in an explicit `CustomerDailyRecordPageResponse` instead of returning Spring `PageImpl` directly, preserving a stable response shape for browser and future mobile clients.
- The controller returns a customer-specific DTO without Customer/Product entity relations or Customer ID. Product labels are fetched through an entity graph to avoid per-row lazy queries.
- Unit price/amount are historical values stored on DailyRecord; product name/unit are current Product labels, since the DailyRecord model does not snapshot those labels.
- A slot with no row cannot be interpreted as a confirmed skipped collection; only explicit persisted statuses carry that meaning. Subscription defaults are not used as actual collection data.
- Date selection uses the configured India business day on the frontend; the API accepts explicit ISO dates and does not infer business meaning from subscription defaults.

## Verification
- Full backend suite: 134 tests passed, 0 failures/errors/skips (`cd backend && ./mvnw clean test`).
- Focused DailyRecord controller/service tests: 30 passed.
- Frontend suite: 25 tests passed; build and lint passed.
- `git diff --check` passed.
- Verification used isolated H2 tests and synthetic records. No live browser-to-backend verification was performed.

## Remaining Work
- Customer subscriptions and issued bill endpoints/views are still unimplemented.
- No payments, balance ledger, or outstanding balance is available.
- Test coverage uses API-client/view-model tests rather than DOM/browser rendering tests; perform browser verification when a dedicated customer test environment is available.

# Customer Authentication and Dashboard Shell (IMPLEMENTED)

## What Was Implemented
- Reused the existing phone/password login and `/api/auth/me` contracts for both OWNER and CUSTOMER sessions.
- Kept the existing tab-scoped `sessionStorage` key so valid owner sessions are not discarded by this update. The login response role is not used to choose a workspace; the workspace is selected only after `/api/auth/me` returns the authenticated role.
- Preserved the owner billing workspace and gated its data loading to OWNER sessions. CUSTOMER sessions render a separate customer shell and never invoke owner APIs.
- Added responsive, keyboard-accessible navigation for Overview, Records, Subscriptions, Bills, and Profile. The non-profile sections are labeled placeholders; no fake dashboard/business data is displayed.
- Implemented the customer Profile view with `GET /api/customer/me`, including loading, retryable error, and session-expiry behavior. It displays only profile fields returned by that API.
- Kept backend behavior and role authorization unchanged.

## Architectural Decisions and Trade-offs
- Reused the single-page app and existing auth API rather than adding a router or auth dependency. The app needs only role-based workspace selection and in-shell customer navigation at this phase.
- Kept `sessionStorage` compatibility and verified role data from `/api/auth/me`; this prevents stale or unverified login response data from selecting protected UI. As before, browser role checks are UX controls, not backend authorization.
- Customer profile requests use `/api/customer/me` and no customer ID. Backend `SecurityConfig` remains responsible for role enforcement.
- Records, subscriptions, bills, payments, and balances remain unimplemented. The customer workspace does not call owner-only APIs or fabricate values.

## Verification
- Frontend tests: 18 passed, 0 failed (authentication/session role selection and customer profile API contract/errors).
- `npm run build`: passed (TypeScript and Vite production build).
- `npm run lint`: passed.
- No browser-to-backend workflow was performed for this phase; these results do not claim live integration verification.
- Backend was not modified; backend tests were not rerun.

## Remaining Work
- Add customer-scoped DailyRecord APIs and implement records/today overview only after cross-customer isolation tests are designed.
- Customer subscriptions and bill views need customer-scoped endpoints; payment history and outstanding balance need separate ledger design.
- No customer dashboard live browser verification was performed.

# DailyRecord Management (IMPLEMENTED)

## What We Did
Added OWNER endpoints to create, filter/list with pagination, retrieve, and same-day-correct actual customer/product collection outcomes.

## Business and Data Rules
- DailyRecord represents actual supply/outcome and remains separate from Subscription's recurring default.
- COLLECTED/EXTRA store positive quantity and an owner-supplied historical unit price snapshot. SKIPPED/PAUSED store zero quantity/amount and no price.
- Amount is calculated server-side from the snapshot, rounded HALF_UP to two decimal places, and persisted.
- Customer, Product, Subscription, date, and slot associations are fixed after creation. Past records cannot be updated.
- An optional Subscription must match Customer/Product and date range; current Subscription status is not used to invalidate historical association.
- One row per customer/product/date/slot is enforced by application check and named database unique constraint.

## Future Work
- BillingPeriod close/reconciliation and Bill generation/issuing are implemented in later milestones. **PLANNED / NOT YET IMPLEMENTED:** Payment and BalanceAdjustment; bills use stored DailyRecord amounts.
- H2 tests validate functional behavior but do not prove concurrent PostgreSQL write safety.

## Tests
DailyRecord service/controller integration tests cover status/quantity rules, snapshots and rounding, eligibility, subscription relationships, duplicate prevention, pagination/filters, same-day correction, historical immutability, DTO boundaries, and OWNER authorization. Final suite totals are recorded in `docs/testing.md`.

## Git Commit
No commit was created as part of this implementation.

# Subscription Management (IMPLEMENTED)

## What We Did
Added OWNER endpoints to create, list/filter, retrieve, and update recurring subscriptions. Subscription stores a default quantity and inclusive effective dates, with lazy references to Customer and Product.

## Business Rules
- ACTIVE subscriptions for the same Customer/Product cannot have overlapping inclusive date ranges; PAUSED/CANCELLED history does not block a new active period.
- New ACTIVE subscriptions require an ACTIVE Customer and Product.
- Customer/Product references are immutable. After a plan starts, quantity and start date cannot be rewritten; shorten the period and create a replacement to change quantity.
- Product price is not copied. Subscription is a plan, not actual milk supply.

## Data Integrity and Future Work
- Application-level overlap detection is IMPLEMENTED.
- **PLANNED / NOT YET IMPLEMENTED:** database-level enforcement against concurrent overlapping PostgreSQL writes. No migration infrastructure or exclusion constraint was added in this module.
- **PLANNED / NOT YET IMPLEMENTED:** customer-facing subscription API and Payment. BillingPeriod closing and Bill generation are implemented separately; DailyRecord snapshots the actual supply price.

## Tests
Service and controller integration tests cover creation, exact quantities/dates, relationship IDs, filters, validation, eligibility, overlap boundaries, updates, historical preservation, DTO boundaries, and OWNER authorization. Final full-suite results are recorded in `docs/testing.md` after verification.

## Git Commit
No commit was created as part of this implementation.

# Billing Period Management (IMPLEMENTED)

## What We Did
Added owner inspection and explicit close operations for canonical calendar-month periods. Read-only inspection does not create a period row; DailyRecord writes and close safely initialize missing periods.

## Reconciliation and Coordination
- Close stores CLOSED state, timestamp, and owner UUID reference and returns an informational COLLECTED/EXTRA count and amount.
- Closing an empty period is allowed, repeated close is idempotent, and closing does not create a Bill.
- Only completed months may close; the Asia/Kolkata business clock makes this independent of the server's configured timezone.
- DailyRecord create/update and close serialize through the same pessimistic lock on the BillingPeriod row. The month row is initialized in a separate transaction and a named unique constraint resolves concurrent first creation.
- Closed periods reject new and backdated DailyRecords and updates. Same-day corrections remain available before close.
- H2 tests exercise application-level races but do not prove PostgreSQL locking or concurrency behavior.

## Future Work
- **PLANNED / NOT YET IMPLEMENTED:** Payment and BalanceAdjustment.
- The reconciliation summary does not prove every physical collection was recorded.

## Tests
BillingPeriod tests cover month parsing/boundaries, read-only lookup, empty/non-empty close summaries, closure metadata, repeated close, DailyRecord write/update restrictions, security, and H2 close-versus-write races. Verified suite totals are recorded in `docs/testing.md`.

## Git Commit
No commit was created as part of this implementation.

# Bill Generation (IMPLEMENTED)

## Workflow and Snapshot
Owner generation creates one nonzero DRAFT per MONTHLY customer and CLOSED calendar month from actual COLLECTED/EXTRA DailyRecords; PREPAID, SKIPPED, and PAUSED are excluded. Header snapshots customer details and period; lines snapshot source record ID/date/slot, product/unit description, quantity, unit price, and stored amount. Bill total is the sum of source amounts. Current Product.price is not used.

Issuing is a separate owner action, records timestamp and issuer, and cannot be reversed through the API. Repeated generation and issue requests return the existing bill. No payments, credits, or BalanceAdjustments are included.

The period-close design does not store customer eligibility snapshots. Bill generation checks current billing type and does not use Customer status as an additional exclusion. Currency is not persisted pending confirmation of INR.

## Verification
Bill tests cover generation, pricing/quantity snapshots, billing-type and record-status eligibility, open/empty/zero periods, retries, issuing, DTO responses, and owner authorization. H2 does not prove PostgreSQL concurrency guarantees.

## Git Commit
No commit was created as part of this implementation.

# Frontend Authentication Flow (IMPLEMENTED)

## What Was Implemented
- Reused the existing phone/password `POST /api/auth/login` and JWT `GET /api/auth/me` contracts.
- Added a shared TypeScript authentication API client with response-shape validation, bearer-header handling, and distinct user-facing 401/403 messages.
- Added a 10-digit phone login field, pending state, credential feedback, and OWNER-role restriction.
- Added explicit session checking/authenticated/anonymous/error states. A restored token is checked with `/api/auth/me` before owner billing renders; 401 clears the tab session, and a failed network check keeps protected content hidden with retry/sign-out controls.
- Kept the existing `sessionStorage` approach and added built-in Node tests for API requests, session-token lifecycle, validation, and role/status handling.

## Architectural Decisions
- Backend authentication and role authorization were not changed; frontend checks improve navigation and messages only.
- The auth API helper is React-independent for reuse and is tested with injected `fetch` implementations without live credentials.
- Node's built-in `node:test` runner avoids adding a frontend test dependency. The tests verify the API client contract, not live browser/backend integration.
- `sessionStorage` is tab-scoped but remains accessible to same-origin scripts. HttpOnly-cookie migration, refresh tokens, revocation, and a CUSTOMER frontend are deferred; details and alternatives are in ADR-009.

## Verification
- Frontend auth client tests: 8 tests, 0 failures.
- Frontend `npm run build`: passed; TypeScript check and Vite production build completed.
- Frontend `npm run lint`: passed.
- Backend `./mvnw -Dtest=AuthControllerTest,JwtServiceTest,SecurityErrorDispatchIntegrationTest test`: 16 tests passed, 0 failures/errors.
- Full backend `./mvnw clean test`: 129 tests passed, 0 failures/errors/skips. The embedded HTTP integration test signs in a synthetic OWNER against the isolated H2 database and validates the JWT using `/api/auth/me`.
- A separate local browser run used headless Chrome, Vite, Spring Boot, and a uniquely named in-memory H2 database containing only synthetic OWNER/CUSTOMER accounts. Browser tests passed for valid OWNER access, invalid credentials, CUSTOMER rejection, session restoration, sign-out, delayed session validation, an expired signed JWT, network failure, and real backend 403 authorization. No business database or real account was used.
- The 403 response was verified against the real backend using a synthetic CUSTOMER token; the owner portal rejects CUSTOMER credentials before storing them. The shared API client separately has focused 401/403 message tests. Browser tests used a real frontend/backend connection, not mocked responses.
- Development uses React StrictMode and performs duplicate auth/session requests during development; the test harness released or failed all matching `/auth/me` requests so this did not leave an artificial request pending.

## Follow-up Browser Verification
After the implementation milestone, the complete local browser auth workflow was verified again against an isolated H2 database. OWNER login and billing data loading, invalid credentials, CUSTOMER rejection, refresh restoration, hidden protected content during delayed `/auth/me`, sign-out token removal, expired-token rejection, network-failure recovery, and backend CUSTOMER-to-owner 403 all passed. The integrated browser tool was unavailable, so the run used the installed headless Chrome over its local DevTools protocol. Credentials and tokens were synthetic and not reported.

# Milestone 1 — Initialize project

## Date
2026-08-13 18:43:24 +0530

## Git Commit
3d0f3e9 — chore: initialize Milk Web project

## What We Did
Created the repository and initial skeleton.

## Why We Did It
Start a codebase for Milk Web.

## Technical Concepts
Repository initialization, basic project layout.

## Important Files
- .gitignore (later)
- initial scaffolding (project root)

## Tests
None

## Notes
FACT: initial commit exists with message above.
INFERENCE: likely created using project scaffolding tools or manually.

# Milestone 2 — Add .gitignore

## Date
2026-08-13 18:53:17 +0530

## Git Commit
6a3e6a1 — chore: add project gitignore

## What We Did
Added a .gitignore to exclude build artifacts and IDE files.

## Why We Did It
Standard maintenance to avoid committing transient files.

## Important Files
- .gitignore

# Milestone 3 — Initialize Spring Boot backend

## Date
2026-08-13 19:48:42 +0530

## Git Commit
79c80dc — feat: initialize Spring Boot backend

## What We Did
Added the backend Spring Boot project with packaging, pom.xml, and initial packages.

## Why We Did It
To implement server-side API and business logic using a mature Java web framework.

## Technical Concepts
- Spring Boot application structure
- Maven build
- Java 21 (pom set to 21)

## Important Files
- backend/pom.xml
- backend/src/main/java/com/radhikafarms/milkweb/MilkWebBackendApplication.java

# Milestone 4 — Initialize React frontend

## Date
2026-08-13 19:53:31 +0530

## Git Commit
221f07f — feat: initialize React frontend

## What We Did
Added a Vite + React + TypeScript frontend skeleton.

## Why We Did It
User interface and future mobile-friendly front-end layer.

## Important Files
- frontend/package.json
- frontend/src/App.tsx

# Milestone 5 — Refactor backend package structure

## Date
2026-08-14 17:33:54 +0530

## Git Commit
06c23af — refactor: organize backend package structure

## What We Did
Reorganized backend Java packages into modules (auth, user, customer...).

## Why We Did It
Better separation of concerns and clearer module boundaries.

# Milestone 6 — Add AI development instructions

## Date
2026-08-17 00:06:48 +0530

## Git Commit
a0f7ef5 — docs: add AI development instructions

## What We Did
Added AGENTS.md containing project rules, architecture notes, domain model guidance, and development policies for AI agents.

## Why We Did It
To capture project conventions, domain model, and provide governance for automated agents.

# Milestone 7 — User domain and tests

## Date
2026-08-17 00:10:33 +0530

## Git Commit
b29cb63 — feat: add user domain and repository test

## What We Did
Implemented User entity, UserRepository, and tests for repository and service.

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/user/User.java
- backend/src/main/java/com/radhikafarms/milkweb/user/UserRepository.java
- backend/src/test/java/com/radhikafarms/milkweb/user/UserRepositoryTest.java
- backend/src/test/java/com/radhikafarms/milkweb/user/UserServiceTest.java

## Tests
Repository and service tests added, relying on H2 test DB.

# Milestone 8 — Authentication and Customer module

## Date
2026-08-17 00:37:57 +0530

## Git Commit
d85341b — feat: implement authentication and customer module

## What We Did
Implemented JWT authentication, JwtService, JwtAuthenticationFilter, AuthController, and Customer entity + repository. Password encoder and security config were added.

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/auth/*
- backend/src/main/java/com/radhikafarms/milkweb/config/*
- backend/src/main/java/com/radhikafarms/milkweb/customer/Customer.java
- backend/src/test/java/.../AuthControllerTest.java
- backend/src/test/java/.../JwtServiceTest.java

## Technical Concepts
- JWT tokens
- BCrypt password hashing
- Spring Security configuration

# Milestone 9 — Customer creation & profile API

## Date
2026-08-20 06:26:52 +0530

## Git Commit
5b3c11e — feat: add customer creation and profile API

## What We Did
Added CustomerService#createCustomer (transactional), CustomerController with POST /api/owner/customers and GET /api/customer/me, DTOs (OwnerCreateCustomerRequest, CustomerResponse), domain exceptions and tests (service + controller).

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerService.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerController.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/OwnerCreateCustomerRequest.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerResponse.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/exception/*
- backend/src/test/java/com/radhikafarms/milkweb/customer/*Test.java

## Tests
After this milestone, backend tests reported:
- 28 tests total
- 28 passed
- 0 failures/errors/skipped

## Notes
- Creation enforces unique phone using UserService and throws 409 on duplicates.
- Customer creation creates a User (role=CUSTOMER) and Customer atomically in a transaction.
- Customer endpoints use role-based access and @AuthenticationPrincipal to identify the caller.

# Milestone 10 — OWNER customer lookup by ID

## Date
2026-08-21 (local milestone work)

## Git Commit
Not yet committed; local implementation only.

## What We Did
Added a read-by-ID endpoint for authenticated owners: GET /api/owner/customers/{customerId}. The controller delegates to CustomerService, which calls CustomerRepository.findById(customerId) and returns a CustomerResponse DTO.

## Why We Did It
The project already supported owner-created customer records and customer self-profile access. The next smallest milestone was to let an OWNER fetch any existing customer profile by its ID without exposing JPA entities or allowing CUSTOMER users to access owner-only routes.

## Technical Concepts
- Repository primary-key lookup
- DTO return contract
- URL-based role authorization
- 404 handling via CustomerNotFoundException

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerService.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerController.java
- backend/src/test/java/com/radhikafarms/milkweb/customer/CustomerControllerTest.java

## Design Decisions
FACT: The project already uses controller/service/repository layering and DTO-based API responses.
INFERENCE: This endpoint follows the same pattern to keep HTTP, business logic, and persistence concerns separate.

## Problems Encountered
No production issue encountered in inspection. The main concern was correctness of authorization and not exposing entity internals.

## How We Solved Them
By using the existing security rules in SecurityConfig, reusing CustomerResponse, and mapping missing customer IDs to CustomerNotFoundException.

## Tests
Added controller tests covering:
- OWNER success
- OWNER 404 for unknown customer
- CUSTOMER forbidden access
- unauthenticated access denied

## Lessons Learned
- Repository primary-key lookups are the simplest way to support read-by-ID in this layered architecture.
- DTOs remain essential for keeping API contracts stable and avoiding entity leakage.

## Interview Takeaway
The owner lookup is intentionally narrow: it allows an OWNER to fetch a specific customer record by ID while preserving the same security and DTO boundaries already used elsewhere in the API.

# Milestone 11 — OWNER customer list

## Date
2026-09-02 (local milestone work)

## Git Commit
Not yet committed; placeholder for new commit.

## What We Did
Implemented GET /api/owner/customers which returns a JSON array of CustomerResponse DTOs for authenticated OWNERs. The controller delegates to CustomerService.listAll(), which calls CustomerRepository.findAll().

## Why We Did It
This milestone provides owners a simple way to retrieve all customer profiles. Pagination and fetch optimizations were intentionally deferred to keep the change minimal and consistent with prior milestones.

## Technical Concepts
- JpaRepository.findAll()
- DTO mapping from entity to API response
- Role-based URL authorization
- Empty list handling (200 OK with [])

## Important Files
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerService.java
- backend/src/main/java/com/radhikafarms/milkweb/customer/CustomerController.java
- backend/src/test/java/com/radhikafarms/milkweb/customer/CustomerControllerTest.java

## Design Decisions
FACT: Reuse existing controller/service/repository pattern and CustomerResponse DTO.
INFERENCE: The system currently expects OWNER to access all customers; per-owner scoping is not implemented in the data model.

## Tests
Added controller tests covering:
- OWNER can list customers
- OWNER gets empty list when none exist
- CUSTOMER is forbidden from listing
- Unauthenticated requests get 401

## Notes
- Pagination was deliberately deferred. If performance issues or UI needs arise, switch to Page/Pageable and add fetch-joins to avoid N+1 queries.

## Lessons Learned
- Small, incremental APIs are easiest to maintain and test.
- Always consider lazy relationships when mapping entities to DTOs (possible N+1).

## Interview Takeaway
We added a simple owner listing endpoint that adheres to the existing layered architecture and respects DTO boundaries; future scaling will introduce pagination and fetch-join optimizations.

# Milestone 4C — OWNER customer profile update

## Date
2026-09-15 (local milestone work)

## Git Commit
Not yet committed; placeholder for new commit.

## What We Did
Implemented PUT /api/owner/customers/{customerId} for OWNER profile updates using OwnerUpdateCustomerRequest and the existing CustomerResponse DTO.

## Design Decision
FACT: The update accepts only name, address, billingType, status, and joinDate.
FACT: Phone is excluded because it is also the User authentication/login identifier.
INFERENCE: A separate phone/account-identifier flow can be introduced later with stronger account-security handling.

## Tests
Added controller and service coverage for successful updates, unchanged phone behavior, missing customers, validation, OWNER authorization, CUSTOMER rejection, unauthenticated rejection, DTO boundaries, and User/Customer consistency.
Verification: `./mvnw clean test` completed with 41 tests, 0 failures, 0 errors, and 0 skipped (BUILD SUCCESS).

## Notes
- Password, passwordHash, role, IDs, createdAt, and nested User data are not accepted or returned.
- The update is transactional, although it changes only Customer fields in this milestone.

# Product Management Module

## Date
2026-09-30 (local module work)

## Git Commit
Not yet committed; commit hash will be recorded after a commit exists.

## What We Did
IMPLEMENTED: Added an independent Product entity and OWNER APIs to create, list, retrieve, and update products. Product responses use DTOs; product status is updated through the general PUT representation.

## Design Decisions
- IMPLEMENTED: Product.price is a current catalog price represented by BigDecimal; no price-history table was added.
- IMPLEMENTED: Product names have a database unique constraint; no normalized name column was introduced.
- IMPLEMENTED: Product status is ACTIVE/INACTIVE and is part of the PUT update request.
- IMPLEMENTED: OWNER list includes active and inactive products; no delete endpoint exists.
- INFERENCE: The initial deployment uses a single currency (likely INR); no currency field or configuration was added.
- PLANNED / NOT YET IMPLEMENTED: DailyRecord will snapshot the applicable unit price so historical records and bills do not depend on current Product.price.

## Technical Concepts
- BigDecimal and NUMERIC(12,2) monetary storage
- String-persisted enums for unit and status
- Database uniqueness constraints
- REST DTO request/response boundaries
- Server-managed timestamps

## Tests
Product service and controller tests cover creation/default status, listing both statuses, lookup and not-found, updates and stable identity/createdAt, duplicate names, validation, DTO boundaries, OWNER authorization, CUSTOMER denial, and unauthenticated denial. Full-suite totals are recorded after the verified test run.
Verification: `./mvnw clean test` completed with 56 tests, 0 failures, 0 errors, and 0 skipped (BUILD SUCCESS), including 9 ProductControllerTest and 6 ProductServiceTest cases.

## Risks and Follow-up
- Product list is unpaginated, appropriate for the initial catalog scope; revisit if measured growth requires paging.
- Database unique constraint protects concurrent duplicate names; database collation determines case matching.
- Subscription, DailyRecord, Billing, Payment, customer catalog access, migrations, and frontend UI remain PLANNED / NOT YET IMPLEMENTED.

## Problems Encountered
No Product-specific implementation problem remained after the full backend suite passed.

## Lessons Learned
- BigDecimal plus an explicit database precision/scale prevents binary floating-point money representation.
- A service duplicate-name check improves the normal response, while the database unique constraint protects the invariant under concurrent writes.
- Current catalog pricing must remain separate from future historical transaction pricing.

## Interview Takeaway
Product stores the current catalog price and uses an explicit status, while future transaction records must snapshot prices to preserve historical billing. OWNER APIs use DTOs and existing role-based security, keeping the catalog backend independent of the web client.

# Java 25 -> Java 21 incident

## Date
2026-08-20 (local)

## Facts
- The committed project POM targeted Java 21.
- Locally, an unstaged edit to backend/pom.xml changed <java.version>21 to 25 and added a PostgreSQL version line.
- The developer's machine and Maven were running Java 21.0.12.
- Running mvn test produced a class-file-version mismatch error.
- The local pom change was discarded (restored to HEAD) and backend tests were re-run and passed.

## Conclusion
FACT: Java 21 is the project baseline. Java 25 was not introduced in history; it was a local unstaged change and was reverted.


# End of development log (most recent milestones recorded)

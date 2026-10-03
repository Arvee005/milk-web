# Development Log

This development log is a chronological record of meaningful milestones in the Milk Web project. Each entry is based on Git history and verified repository contents. Facts are drawn from commit metadata and code; where reasons are not recorded, they are marked as INFERENCE.

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

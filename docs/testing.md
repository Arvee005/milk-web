# Testing

This document explains how tests are organized and executed for Milk Web.

Test frameworks and tools
- Maven (./mvnw) is used to run tests
- JUnit (via Spring Boot test starters)
- Spring Boot testing support (SpringBootTest, DataJpaTest)
- MockMvc for controller/integration tests
- H2 in-memory database for tests (configured in `src/test/resources/application.properties`; the development profile also uses H2)
- The frontend uses Node's built-in `node:test` runner for authentication and customer API/view-model tests. React component tests use Testing Library with jsdom, and `tsx` loads the JSX test entry point.

Test types and purpose
- Repository tests (DataJpaTest): verify correct persistence mappings and repository queries.
- Service tests (SpringBootTest or unit tests): verify business logic and transactional behavior.
- Controller tests (SpringBootTest + AutoConfigureMockMvc): verify HTTP endpoints, validation, security, and DTO boundaries.

How to run tests
- In project root:
  cd backend && ./mvnw test
- Frontend API and React component tests: `cd frontend && npm test` (Node.js v22.6 or newer for built-in TypeScript stripping).
- Frontend production build and lint: `cd frontend && npm run build` and `cd frontend && npm run lint`.

Test status (current)
- After login/registration rate limiting (2026-10-15): backend `./mvnw package` ran 205 tests, 0 failures, 0 errors, 0 skipped. Frontend `npm test` ran 78 API client tests and 65 React component tests, all passing. `AuthRateLimiterTest` (6, controllable clock) covers per-phone blocking from any IP, window expiry and the remaining-time value, success clearing the counter, per-IP blocking across phones, registration limits, and the key cap. `AuthRateLimitTest` (3, HTTP) covers 429 + `Retry-After` even with the right password, other accounts still signing in, and per-IP registration limits. Test properties raise the limits so shared contexts do not interfere; `AuthRateLimitTest` uses its own H2 database.
- After customer self-registration (2026-10-15): backend `./mvnw package` ran 196 tests, 0 failures, 0 errors, 0 skipped. Frontend `npm test` ran 76 API client tests and 65 React component tests, all passing; `npm run lint` clean; `npm run build` succeeded. Older counts below are historical.
- Registration tests: `RegistrationControllerTest` (9) covers success and stored values (CUSTOMER, PENDING, MONTHLY, India join date, BCrypt hash), no password or hash in the response, the token reaching `/api/customer/me` but getting 403 on owner endpoints, login afterwards, ignored `role`/`userId`/`status`/`billingType`/`joinDate`, duplicate phone and OWNER phone (409, existing account unchanged), owner subscription creation rejected for a PENDING customer, invalid phone/password/name/address (including the 72-byte limit with multi-byte characters), and the maximum valid password. `CustomerRegistrationConcurrencyTest` (1) runs four simultaneous registrations for one phone and requires exactly one success and three 409-type conflicts.
- Frontend registration tests: `authApi.test.ts` (request body without extra fields, client validation without a request, byte-based password limit, 409 message, malformed response), PENDING acceptance in `customerApi.test.ts` and `ownerCustomersApi.test.ts`, and `customerRegistration.component.test.tsx` (7: successful submit, digit-only phone, password mismatch, short password and blank name, 409 with details kept, network failure, switch to sign in).
- The full backend suite now contains 154 tests.
- Test result: 154 tests run, 0 failures, 0 errors, and 0 skipped (`cd backend && ./mvnw clean test`).
- The focused DailyRecord controller/service run contained 30 tests, all passing.
- Frontend test result after the owner DailyRecord entry/review slice: 39 API client tests (`authApi`, `customerApi`, `dailyRecordsApi`, `subscriptionsApi`, `billsApi`, `ownerDailyRecordsApi`) and 17 React component tests (6 customer bills, 11 owner DailyRecords), all passing, run as two sequential `node --test` invocations by `npm test`.
- Frontend test result after the owner customer and product setup slice: 52 API client tests (the 39 above plus 7 `ownerCustomersApi` and 6 `ownerProductsApi`) and 43 React component tests (6 customer bills, 12 owner DailyRecords, 12 owner Customers, 13 owner Products), all passing. `npm test` enumerates every test file explicitly, so a new file must be added to the script to run.
- Frontend auth tests cover OWNER/CUSTOMER role routing from `/api/auth/me`, login, restoration-related API behavior, invalid credentials, token handling, and 401/403/network distinctions. Customer profile, DailyRecord, subscription, and bill API tests cover request URL/filters, no customer ID in requests, response validation, status/date labels, missing-slot representation, filter validation, pagination state, loading/empty/error handling, grouping, retry, and auth/network failures. These API tests use injected fetch functions and pure view models, not live HTTP.
- Owner DailyRecord API client tests cover owner customer/product loading, query construction for the active filters with page/size, create and update payload shapes, rejection of malformed records/products, and 401/403/409 handling.
- Owner DailyRecord component tests render the workspace in jsdom with synthetic responses. They cover customer/product loading, chargeable creation with the selected date/slot/quantity/price snapshot, client validation that sends no request, zero-quantity SKIPPED creation, the empty state, rejected creation and rejected correction, reference-loading failure with retry, filter application, 403 visibility with 401 session expiry, and same-day correction preserving the original price snapshot. They are DOM component tests, not live-browser or backend integration tests.
- Customer bills component tests render the customer workspace in jsdom with synthetic API responses. They verify issued-bill period/total display, expandable line summaries, empty/loading/error/retry states, pagination requests, and the absence of paid/unpaid claims. They are DOM component tests, not live-browser or backend integration tests.
- Frontend `npm run build` passed (TypeScript and Vite production build); `npm run lint` passed.
- `git diff --check` passed after the Phase 4 changes.
- Phase 2 backend tests use isolated H2 data and synthetic OWNER/CUSTOMER entities. They verify own-customer scoping despite another customer's matching records, rejection of customerId query input, product/price/status DTO fields, range/slot filters, pagination, bad dates/enums/ranges/page limits, unauthenticated rejection, and existing CUSTOMER denial on owner DailyRecord endpoints.
- No live browser-to-backend verification was performed for Phase 2. No claim of end-to-end browser verification is made.
- No real business data was used or modified.

## Production database and migration validation (PROPOSED; NOT IMPLEMENTED)

Current backend persistence tests use H2 and Hibernate `create-drop`; there is no migration history, PostgreSQL test profile, migration test, or production schema-validation result. Existing H2 test success does not verify PostgreSQL DDL compatibility, locks, isolation, timestamp behavior, or baseline safety.

If Flyway is adopted, the recommended CI migration suite should use disposable PostgreSQL databases and cover:
- Creating a fresh database by applying all migrations in order, checking Flyway history/checksums and Hibernate `validate`.
- Upgrading from the previous schema version with synthetic representative rows, asserting data preservation, FK/unique/check constraints, and expected transformations.
- A controlled baseline test against a reconstructed pre-existing schema, verifying that baselining changes no business data and later migrations apply.
- A disposable intentional migration failure, confirming deployment/readiness stops and the recovery steps are accurate.
- PostgreSQL-only UUID, decimal, date/instant, constraint, delete behavior, transaction rollback, locking, deadlock, and concurrent financial-allocation tests.

Before each production release, restore a protected backup into an isolated rehearsal target, run the exact migration artifact from the actual prior schema, check row counts/invariants and timing/locks, and rehearse compatible application rollback or restore/forward-fix. Run restore drills periodically. No migration, baseline, PostgreSQL integration suite, or restore was run for the documentation-only strategy phase.

Keep H2 for fast application tests if valuable; use PostgreSQL-backed tests for the database behavior that production depends on. Do not use production data in ordinary CI or shared test environments.

Phase 3 customer-subscription coverage:
- Backend integration tests use synthetic customers and products in the isolated H2 test environment. They verify current, future, ended, paused, and cancelled combinations; inclusive date boundaries; correct product summary DTO; cross-customer isolation; customerId rejection; OWNER denial; and anonymous rejection.
- Frontend tests cover current/other grouping, separate lifecycle status and date-range state, loading/empty/error states, endpoint and bearer-auth usage without a customer ID, invalid response handling, and 401/403/network failures.
- Subscription frontend tests use injected fetch and view-model functions; they do not claim DOM/browser integration or live browser-to-backend verification.
- DailyRecord tests asserting today's behavior use the configured India business Clock so they remain correct when the host timezone's local date differs from India.

Phase 4 customer-bill coverage:
- Backend tests use synthetic bills and verify only ISSUED bills with issue metadata are visible, including exclusion of DRAFT bills, ownership isolation, customerId rejection, CUSTOMER-only authorization, bounded pagination, stable ordering, line snapshots, and customer-safe DTO fields.
- Focused `BillControllerTest`: 4 passed. Frontend bill tests cover paged response validation, period/date/amount/status fields, loading and empty/error state models, retry, and 401/403/network errors. No payment status is included or inferred.
- No live browser-to-backend bill workflow was performed.

Frontend API tests use injected fetch functions; the customer bills component tests use jsdom and synthetic responses. Neither approach claims live HTTP integration or browser verification.
- Frontend production type-check/build and lint are run with `cd frontend && npm run build` and `cd frontend && npm run lint`.
- Local browser authentication verification used headless Chrome against the actual Vite frontend and Spring Boot backend. The backend used an isolated, uniquely named in-memory H2 database seeded only with synthetic OWNER/CUSTOMER test accounts. Verified owner login and billing data load, invalid credentials, customer rejection without token storage, session restoration, delayed `/auth/me` protected-content gating, sign-out/token removal, a validly signed expired JWT, simulated network failure during session validation, and HTTP 403 for a CUSTOMER bearer on an owner endpoint.
- The browser run verified HTTP 403 from the backend and the API-client unit tests verify its user-facing permission message; the CUSTOMER owner portal intentionally stops at the role check rather than rendering an owner page that could exercise that message in-context.
- No real business data was accessed or modified. The local H2 server, Spring Boot process, Vite process, and isolated browser profile are stopped/removed after verification.

New controller tests added for GET /api/owner/customers:
- ownerCanListCustomers: verifies OWNER can retrieve a JSON array of CustomerResponse
- ownerGetsEmptyListWhenNoCustomers: verifies empty array returned when no customers
- customerCannotGetOwnerCustomerEndpoint: verifies CUSTOMER receives 403 for list endpoint
- unauthenticatedCannotGetOwnerCustomer: verifies 401 for unauthenticated requests

What these tests protect:
- Role-based access control for owner-only endpoints
- DTO boundary to prevent leaking JPA entities or sensitive fields
- Correct empty-list behaviour (200 with [])

Test best practices
- Use H2 for fast, isolated tests and avoid depending on production DB state.
- Keep tests deterministic and transactional where appropriate.
- Test both positive and negative flows (duplicate phone, unauthorized access, validation failures).

What tests protect
- Authentication and authorization behavior (AuthControllerTest)
- Password hashing and user creation (UserServiceTest)
- JWT generation and validation (JwtServiceTest)
- Customer creation transactional behavior and duplicate handling (CustomerServiceTest)
- Controller authorization rules and error mapping (CustomerControllerTest)

When to add tests
- Add repository tests when adding entities or custom queries.
- Add service tests for business rules.
- Add controller tests for endpoint behavior and authorization.

Milestone 4C update tests:
- Controller tests cover successful OWNER updates, exact updated values, unchanged phone behavior when a `phone` request property is supplied, absence of `passwordHash` and nested `user`, 404 for unknown customers, 400 for blank name, missing required fields, malformed date, invalid enum, malformed UUID, 403 CUSTOMER access, and 401 unauthenticated access.
- Service tests cover successful updates to all editable fields, unchanged User phone/password hash/role, and `CustomerNotFoundException` for an unknown customer.

## Product Management tests
Product service tests cover:
- creation with ACTIVE default and generated timestamps
- duplicate product names
- updating name, description, unit, price, and status while preserving ID and createdAt
- unknown product reads/updates
- duplicate-name update rejection and retaining a product's own name

Product controller tests cover:
- OWNER create (201, Location, DTO fields, default status)
- OWNER list containing ACTIVE and INACTIVE products
- get success and 404
- full update response and stable ID/createdAt
- duplicate create/update conflicts
- invalid/missing fields, enum values, price range/scale, and malformed UUID
- CUSTOMER 403 and unauthenticated 401 for all OWNER operations
- DTO boundary checks for internal fields and future relationships

No ProductRepositoryTest was added: the module uses only the derived duplicate-name query plus inherited JpaRepository operations; persistence and constraint behavior are exercised through service/controller integration tests.

At Product module implementation, verification reported 15 Product tests (9 controller, 6 service); the full suite at that point contained 56 tests.

## Subscription Management tests
Subscription service tests cover:
- ACTIVE default, exact `BigDecimal` quantity, Customer/Product references, dates, and timestamps
- missing/inactive Customer and Product handling
- exact, partial, containing, open-ended, inclusive-boundary, adjacent, and one-day overlap behavior
- paused/cancelled history, reactivation overlap/eligibility checks, and update overlap checks
- filtered lookup by Customer, Product, status, and effective date
- future edits, started-period restrictions, allowed end-date/status changes, and history preservation

Subscription controller tests cover:
- OWNER create (201, Location, response DTO fields/default status), list/filter, get, and update
- required fields, quantity range/scale, dates, enum and UUID binding errors
- related-entity 404/409 behavior and overlap 409
- DTO boundary, immutable Customer/Product references, 401 unauthenticated and 403 CUSTOMER access

No SubscriptionRepositoryTest was added; custom overlap/filter query behavior is exercised through service/controller integration tests.
**PLANNED / NOT YET IMPLEMENTED:** PostgreSQL database-level concurrent-overlap enforcement. H2 tests do not prove concurrent PostgreSQL safety.

Subscription module verification: 23 Subscription tests (9 controller, 14 service); full suite total 79 tests, 0 failures, 0 errors, 0 skipped.

## DailyRecord Management tests
DailyRecord service tests cover:
- COLLECTED, EXTRA, SKIPPED, and PAUSED records with exact quantities, relationships, timestamps, prices, and amounts
- HALF_UP amount rounding, status/quantity/price consistency, and precision/range validation
- past-date backfill with explicit price, Product price changes preserving existing snapshots, and same-day-only updates
- optional Subscription association, Customer/Product/date matching, inclusive boundaries, ongoing periods, and paused/cancelled historical subscriptions
- ACTIVE customer/product eligibility for supplied quantity, unknown related entities, duplicate event rejection for every status
- quantity corrections, immutable price snapshots, no-supply-to-supply transitions, filters, and pagination

DailyRecord controller tests cover:
- OWNER create (201, Location, response DTO and server-calculated amount), list/filter/page ordering, get, and update
- request validation, malformed query/path values, date-range rules, duplicate and eligibility conflicts, unknown related records
- 401 unauthenticated and 403 CUSTOMER behavior for all owner routes
- client-controlled ID/amount/timestamp exclusion and response DTO boundaries

DailyRecord verification: 25 tests (11 controller, 14 service); full suite total 104 tests, 0 failures, 0 errors, 0 skipped.
H2 validates the named uniqueness constraint and application behavior, but does not establish PostgreSQL concurrent-write safety.

Owner DailyRecord Entry and Review tests
No backend test was added for this slice because no backend code, endpoint, DTO, or rule changed; the existing DailyRecord controller/service coverage above remains authoritative.

- `ownerDailyRecordsApi.test.ts`: owner customer/product reference loading; only-active-filter query construction including page and page size; create payload with an explicit unit-price snapshot and no subscription; update payload limited to quantity/status/price with the record ID in the path; rejection of an invalid slot and an invalid product unit; and preservation of 401, 403, and 409 statuses.
- `ownerDailyRecords.component.test.tsx`: reference loading state, chargeable creation (EVENING, EXTRA, quantity 2.5, price 65.50) with the created card visible in the review list, quantity validation with no POST issued, SKIPPED creation sending zero quantity and a null price, empty state, rejected creation retaining entered values, rejected correction, customer-loading failure with a working retry, applied date/customer/product/slot/status filters, 403 visibility with 401 session expiry, and same-day correction sending the unchanged original price snapshot.

These use injected fetch and jsdom; no live browser-to-backend DailyRecord entry workflow was performed and none is claimed.

Owner Customer and Product Setup tests
No backend test was added for this slice because no backend code, endpoint, DTO, rule, or schema changed; the existing customer (25) and product (15) controller/service/repository coverage above remains authoritative and all 138 backend tests still pass.

- `ownerCustomersApi.test.ts` (7 tests): the full customer list response including address, billing type, and join date; the exact create payload including the initial password; a null address when the field is left blank; the update payload containing every full-replacement field while asserting `phone` and `password` are absent; 401/403/409 status preservation across both create and update; malformed-response rejection for an unknown status, a non-ISO join date, and an unknown billing type; client-side search by name and by phone combined with status filtering; and status/billing-type labels.
- `ownerProductsApi.test.ts` (6 tests): the full product list response; the create payload with `status` explicitly asserted absent and the server-returned status asserted `ACTIVE`; the update payload with `status` included; 401/403/409 status preservation; malformed-response rejection for an unknown unit, a non-numeric price, and an unknown status; empty and malformed `createdAt`/`updatedAt` rejection covering `""`, `"bad"`, `"2026-13-45"`, and the impossible date `"2026-02-30"`, with a valid leap-free timestamp still accepted; and price/unit/status formatting.
- `ownerCustomers.component.test.tsx` (12 tests): loading state, empty state, load failure with a working retry, search and status filtering with the list request count asserted at one, a no-match state, successful creation asserting the exact request body, client validation blocking submission with no POST issued, a duplicate-phone 409 that preserves the entered name and phone, editing that asserts the complete full-replacement body plus a read-only phone and no password field, a list-level status change asserting a full-replacement body, 403 remaining visible without expiring the session, and 401 expiring the session.
- `ownerProducts.component.test.tsx` (13 tests): loading state, empty state, load failure with retry, creation asserting `status` is absent from the body, client validation for a missing name, a malformed price, and excess decimal places, a duplicate-name 409 preserving values, a price/description edit that requires no confirmation, a name change that blocks the request and requires "Confirm and save", a unit-only change requiring the same confirmation, the warning clearing when the original name is restored, deactivate then activate asserting both full-replacement bodies, 403 remaining visible, and 401 expiring the session.

These use injected fetch and jsdom; no live browser-to-backend customer or product setup workflow was performed and none is claimed.

## BillingPeriod Management tests
BillingPeriod service tests cover:
- valid `YYYY-MM` periods, invalid identifiers, canonical boundaries, and leap-year February
- current/future inspection versus rejection of current/future close using the explicit business clock
- read-only inspection without creating a persisted OPEN row
- close metadata, empty-period close, repeated idempotent close, and chargeable count/amount
- inclusion of COLLECTED/EXTRA and exclusion of SKIPPED/PAUSED
- blocking new/backdated DailyRecords and updates after closure while preserving same-day correction in OPEN periods

BillingPeriod controller tests cover:
- OWNER inspection/close and response DTO fields
- malformed/impossible period input as 400
- current/future inspection allowed and close rejected with 409
- DailyRecord create into a closed period returns 409
- unauthenticated 401 and CUSTOMER 403

Separate-transaction H2 race tests cover simultaneous first period initialization with close versus DailyRecord creation, and close versus a past-record update attempt. Past records are independently immutable under the existing DailyRecord rule, so the latter exercises lock ordering but cannot demonstrate a successful concurrent correction. These tests do not prove PostgreSQL lock scheduling, isolation, or concurrency safety.

BillingPeriod verification: 14 tests (7 service, 5 controller, 2 concurrency); focused BillingPeriod and DailyRecord verification at that milestone: 39 tests, 0 failures, 0 errors, 0 skipped. The suite later passed with the totals recorded below.

## Bill Generation tests
Bill service tests cover:
- generation for a MONTHLY customer using persisted COLLECTED/EXTRA records and stored amounts
- exclusion of PREPAID customers and SKIPPED/PAUSED records
- preservation of quantity, unit-price, amount, customer, and product/unit description snapshots
- rejection for open, empty, and zero-total periods without creating a Bill
- duplicate generation returning the existing Bill
- DRAFT-to-ISSUED transition, idempotent reissue, and prevention of source DailyRecord edits after close

Bill controller tests cover:
- OWNER generation, retrieval, filtered pagination, and issuing
- malformed period (400), open period / ineligible state conflicts (409), and missing bill (404)
- unauthenticated 401 and CUSTOMER 403 access restrictions

The period row lock serializes bill generation per closed period; database unique constraints protect customer-period and source-record uniqueness. Tests use H2 and do not prove PostgreSQL-specific lock/concurrency behavior.

Bill verification: 6 tests (4 service, 2 controller); combined BillingPeriod, DailyRecord, and Bill verification: 45 tests, 0 failures, 0 errors, 0 skipped. Full-suite verification after Bill Generation: 124 tests, 0 failures, 0 errors, 0 skipped.

## Development owner seeding tests

`DevOwnerSeederTest` (15 tests) drives the runner directly against the real `UserService` and the isolated H2
test database, with a `StandardEnvironment` supplying the profile list and properties:
- the seeder bean is not registered when the `dev` profile is inactive
- a missing phone creates nothing, and neither does a phone that is not exactly 10 digits (`12345`,
  `12345678901`, `+919000000001`, `90000A0001`); surrounding whitespace is trimmed rather than rejected
- a missing or blank password creates nothing, and neither does a password shorter than 8 characters
- seeding is skipped when `dev` is not the only active profile (`dev,prod`, `prod`, `default`)
- creation produces an OWNER whose stored hash differs from the raw password, is a BCrypt hash, and verifies
  through `UserService.matchesPassword`
- a second run does not re-create or re-hash the account
- an existing OWNER keeps its original password, and an existing CUSTOMER is not promoted
- a password over BCrypt's 72-byte limit creates nothing, checked three ways: 73 ASCII characters; a
  2-byte-per-character password of 37 characters that is 74 bytes and so exceeds the limit by bytes while
  remaining under it by characters; and a 4-byte-per-code-point password of 19 code points that is 76 bytes
- a non-ASCII password within the limit (30 characters, 60 bytes) is still accepted and its hash verifies,
  so the byte check does not over-reject

`DevOwnerSeederDevProfileTest` (1 test) is the wiring check: with `@ActiveProfiles("dev")` and the owner
settings supplied as test properties, the bean exists and the owner is already in the database when the test
body runs, because `ApplicationRunner` executes during context startup. It uses the `application-dev.properties`
H2 database, so it is isolated from the other tests, and it deletes the seeded owner afterwards.

The two assertions live in one test method on purpose: the runner fires once per application context, not
once per test method, so separate methods would race with the cleanup.

## Payment and Balance test strategy (PROPOSED; NOT IMPLEMENTED)

No payment, allocation, adjustment, or balance tests exist because the financial domain has not been implemented. These are required future tests, not current coverage:

| Area | Synthetic scenarios | Invariants/assertions |
|---|---|---|
| Receipts | Exact, partial, excess, advance, no-bill, and duplicate retry | Receipt total represents money received exactly once; identical idempotent retries return one receipt; same key/different payload conflicts. |
| Allocation | One receipt to one/multiple bills; multiple receipts to one bill; unallocated remainder; later explicit allocation | For each receipt, `0 <= net allocations <= received`; unallocated cash equals received less net allocations; allocations never exceed eligible issued-bill remainder. |
| Eligibility/isolation | DRAFT bill, wrong customer, wrong currency, unknown bill, OWNER/CUSTOMER/anonymous requests | Only authenticated OWNER can write; all payment and bill associations belong to the same customer; CUSTOMER reads expose only principal-linked account data; non-issued bills cannot be allocated. |
| Adjustments/corrections | Customer and bill-scoped debit/credit, correction with reference, attempted mutation/deletion | Adjustment is not counted as cash; posted event history is append-only and actor/reason attributable; corrections preserve source linkage. |
| Bill correction | Existing allocation followed by approved bill correction/void path | Preserve the original issued bill and allocation history; no resulting bill balance is negative. Exact expected behavior remains blocked on owner policy. |
| Concurrency | Concurrent allocations from one receipt and concurrent payments toward one bill | Final receipt allocation never exceeds receipt amount; final bill allocation never exceeds eligible bill obligation; operations serialize or one conflicts without partial writes. Run on PostgreSQL as well as fast H2 tests. |
| Balance invariants | Mixed bills, allocations, unallocated receipts, and scoped adjustments | `bill outstanding = issued total + bill debits - bill credits - net allocations`; `customer net due = sum bill outstanding + account debits - account credits - unallocated cash`; due and account-credit displays are nonnegative and never both positive. |
| Compatibility/precision | Legacy bills with unresolved currency; fractional/overflow inputs | No guessed historical currency; unresolved bills cannot accept financial allocations; monetary values use exact supported decimal scale and reject overflow/unsupported fractions. |
| Read APIs | Stable pagination, event ordering, customer/bill filters | Stable `effectiveAt DESC, id DESC`; bounds enforced; customer ID cannot broaden reads; summaries reconcile to event calculations. |

Use isolated synthetic data only. H2 tests do not prove PostgreSQL lock/isolation behavior. Before implementation, select a migration process and add PostgreSQL-backed concurrency/reconciliation validation.

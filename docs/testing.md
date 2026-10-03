# Testing

This document explains how tests are organized and executed for Milk Web.

Test frameworks and tools
- Maven (./mvnw) is used to run tests
- JUnit (via Spring Boot test starters)
- Spring Boot testing support (SpringBootTest, DataJpaTest)
- MockMvc for controller/integration tests
- H2 in-memory database for tests (configured in application-dev.properties)

Test types and purpose
- Repository tests (DataJpaTest): verify correct persistence mappings and repository queries.
- Service tests (SpringBootTest or unit tests): verify business logic and transactional behavior.
- Controller tests (SpringBootTest + AutoConfigureMockMvc): verify HTTP endpoints, validation, security, and DTO boundaries.

How to run tests
- In project root:
  cd backend && ./mvnw test

Test status (current)
- The verified backend suite contains 118 tests.
- Test result: 118 tests run, 0 failures, 0 errors, and 0 skipped.
- The suite was verified with `cd backend && ./mvnw clean test`.

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

BillingPeriod verification: 14 tests (7 service, 5 controller, 2 concurrency); focused BillingPeriod and DailyRecord verification: 39 tests, 0 failures, 0 errors, 0 skipped. Full-suite verification after this milestone: 118 tests, 0 failures, 0 errors, 0 skipped.

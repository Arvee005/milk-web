# Development Log

This development log is a chronological record of meaningful milestones in the Milk Web project. Each entry is based on Git history and verified repository contents. Facts are drawn from commit metadata and code; where reasons are not recorded, they are marked as INFERENCE.

# Owner Customer and Product Setup (IMPLEMENTED)

## Previous checkpoint
`7ae1c25` — `feat: add owner daily record entry and review`

## What We Did
IMPLEMENTED: Replaced the disabled Owner "Customers" navigation placeholder with a working section (`frontend/src/OwnerCustomers.tsx`, `frontend/src/ownerCustomersApi.ts`) and added a new "Products" section (`frontend/src/OwnerProducts.tsx`, `frontend/src/ownerProductsApi.ts`). The owner can create customer accounts, browse and search the customer directory, edit customer details, and activate or deactivate a customer. The owner can create products, edit product details and prices, and activate or deactivate a product.

No backend code changed. Every operation uses endpoints that already existed: `POST`/`GET`/`PUT /api/owner/customers`, `GET`/`POST`/`PUT /api/owner/products`. INFERENCE: no backend gap was found during reconnaissance, so adding an endpoint would have duplicated existing authority.

## Design Decisions
- IMPLEMENTED: two separate owner sections, selected by widening the existing `OwnerSection` union in `frontend/src/App.tsx` to `'billing' | 'daily-records' | 'customers' | 'products'`. No router and no global state library was introduced; this matches the existing early-return section pattern.
- IMPLEMENTED: customer search and status filtering run in the browser over the full list from `GET /api/owner/customers`. `CustomerRepository` has no query methods and the list endpoint takes no parameters, so a client-side filter was the only option that avoided a backend change. The filter logic lives in the exported, unit-tested `filterOwnerCustomers` view-model function.
- IMPLEMENTED: the customer update form always submits `name`, `address`, `billingType`, `status`, and `joinDate`. The backend `PUT` is a full replacement with `@NotNull` on the last three, so a partial body returns 400. The list status buttons build the same complete body from the stored customer rather than sending a status-only patch.
- IMPLEMENTED: `phone` is read-only in edit mode and is never sent on update. `OwnerUpdateCustomerRequest` has no phone field, and `ownerCannotChangePhoneThroughUpdate` already asserts the server rejects any attempt.
- IMPLEMENTED: product creation omits `status` entirely because `ProductService.createProduct` always provisions `ProductStatus.ACTIVE`; the status control only renders in edit mode. Product update always includes the current status, matching the full-replacement `OwnerUpdateProductRequest`.
- IMPLEMENTED: renaming a product or changing its unit requires an explicit second confirmation before the `PUT` is sent. `CustomerDailyRecordController` and `CustomerSubscriptionController` resolve `getProduct().getName()` and `getUnit()` live, so those edits change how already-collected records are labelled. Money is unaffected and the warning states this.
- IMPLEMENTED: there is no delete action for either entity. `ProductStatus.INACTIVE` is the only removal mechanism the domain supports, so deactivation is presented as the reversible alternative.
- IMPLEMENTED: 401 clears the session through the existing callback; 403 renders as a permission error and does not expire the session; 409 and 400 render the server message and preserve entered form values.
- IMPLEMENTED: browser-side checks mirror only the shape the server enforces (10-digit phone, 8-character password, required name, price of at most 10 whole digits and 2 decimals, length limits). They are usability guards, not a second rule engine.
- IMPLEMENTED: the product response guard validates that the `createdAt`/`updatedAt` date segment is a real calendar date, because the product list renders it through a formatter that throws on an invalid date. A malformed timestamp is rejected at the guard as invalid product data instead of crashing the section during render. This matches the existing `validDate` convention used for customer `joinDate` and DailyRecord dates.

## Trade-offs
- Client-side search and filtering load every customer on each visit. At a family-dairy customer count this is cheap, but it does not scale; moving search server-side would need new repository query methods and list parameters.
- The owner sidebar markup is duplicated in each owner section, so adding navigation meant editing three files. This was left in place deliberately: extracting a shared shell would have touched already-committed, passing code for no functional gain in this slice.
- The customer directory is a card list rather than a table, matching the existing record-review layout and staying usable on small screens.
- The name/unit confirmation is a second click rather than a modal, keeping it inside the existing form flow and avoiding a new dialog pattern.

## Security implications
- No new route, DTO, authorization rule, or filter chain was added. `SecurityConfig` still enforces `/api/owner/**` as ROLE_OWNER and was not modified.
- The customer form posts a plaintext password to `POST /api/owner/customers` exactly once, over the existing authenticated channel. The password is never read back, never stored in browser storage, and is not present in any response DTO. `CustomerResponse` contains no `passwordHash`, so no hash can reach the browser.
- Because no password-reset or password-change endpoint exists anywhere in the backend, a forgotten customer password cannot be recovered by the owner. This is a real limitation and is now stated in the UI next to the password field and in the success notice after creation.
- The password input uses `type="password"` with `autoComplete="new-password"`, and the field is removed entirely in edit mode so an existing password is never re-rendered or resent.
- Client and server error objects are never rendered directly; only the message string extracted by `apiRequest` is displayed.

## Tests
- `frontend/src/ownerCustomersApi.test.ts` (7 tests): full customer list response, exact create payload including the initial password, null address when blank, update payload containing every full-replacement field and neither phone nor password, 401/403/409 status preservation on both create and update, malformed-response rejection for a bad status, a non-ISO join date, and a bad billing type, client-side search/status filtering, and status/billing-type labels.
- `frontend/src/ownerProductsApi.test.ts` (6 tests): full product list response, create payload with an asserted-absent `status` field, update payload including `status`, 401/403/409 status preservation, malformed-response rejection for a bad unit, a non-numeric price, and a bad status, empty and malformed `createdAt`/`updatedAt` rejection (`""`, `"bad"`, `"2026-13-45"`, `"2026-02-30"`) with a valid timestamp still accepted, and price/unit/status formatting.
- `frontend/src/ownerCustomers.component.test.tsx` (12 tests): loading state, empty state, load failure with a working retry, search and status filtering with an asserted single list request, no-match state, successful creation asserting the exact body, client validation blocking the request with no POST issued, duplicate-phone 409 preserving entered values, editing with the complete body and a read-only phone, list status change with a full-replacement body, 403 visible without session expiry, and 401 expiring the session.
- `frontend/src/ownerProducts.component.test.tsx` (13 tests): loading state, empty state, load failure with retry, creation asserting no `status` in the body, client validation for missing name, malformed price, and excess decimals, duplicate-name 409 preserving values, price/description edit with no confirmation required, name change requiring confirmation and explaining the effect, unit-only change requiring confirmation, warning clearing when the name is restored, deactivate/reactivate with full-replacement bodies, 403 visible, and 401 expiring the session.
- No backend test was added: no backend behaviour changed.
- Verification: `./mvnw clean test` = 138 tests, 0 failures, 0 errors, 0 skipped. `npm test` = 52 API client tests + 43 component tests, 0 failures. `npx tsc -b`, `npm run build`, and `npm run lint` passed.

## Problems Encountered
- The first draft nested the help spans for "Initial password", "Search", and "Price per unit" inside their `<label>` elements. That pollutes each control's accessible name with the hint text and broke `getByLabelText`. The same defect was already recorded for the price field in the previous slice; all three were moved to a `catalog-field` wrapper with a sibling label referenced by `aria-describedby`.
- Both sections initially rendered their own "Loading …" status block in the entry card and again in the review area, producing duplicate live-region text. The review area now renders nothing while loading, leaving the entry card as the single loading announcement.
- The product confirmation originally stored an "acknowledged" flag in state and still required a second click on Save. Passing the confirmation explicitly into the save call makes "Confirm and save" a single action and removes the unused state.

## Lessons Learned
- A full-replacement `PUT` contract is easy to violate from the UI, because a form naturally sends only what changed. Encoding the complete body in the request type and in the list-level status action makes the constraint visible to the type checker rather than leaving it to a 400 at runtime.
- Before enabling an edit that touches denormalized display data, tracing every read path for that entity is what surfaced the live product-label join. The monetary path was already snapshot-protected; the label path was not.

## Interview Takeaway
Owner customer and product setup was a frontend-only slice because the domain APIs were already complete and authoritative. The interesting work was not the CRUD wiring but respecting the contract: full-replacement updates, an immutable phone, a server-owned initial status on create, and a confirmation gate for the one edit that is not historically immutable.

## Git Commit
Not yet committed; commit hash will be recorded after a commit exists.

# Owner DailyRecord Entry and Review (IMPLEMENTED)

## Previous checkpoint
`e45cfd0` — `feat: add customer dashboard and domain planning`

## What We Did
IMPLEMENTED: Replaced the disabled Owner "Daily records" navigation placeholder in `frontend/src/App.tsx` with a working entry and review workspace (`frontend/src/OwnerDailyRecords.tsx`) backed by a dedicated API client (`frontend/src/ownerDailyRecordsApi.ts`). The owner can select a customer, product, date, MORNING/EVENING slot, status, and actual quantity, then save; the review list shows the same records with filters and pagination.

No backend code changed. The workspace reuses the existing OWNER DailyRecord endpoints (`POST`/`GET`/`PUT /api/owner/daily-records`), `GET /api/owner/customers`, and `GET /api/owner/products`. INFERENCE: no backend gap was found, so adding a new endpoint would have duplicated existing authority.

## Design Decisions
- IMPLEMENTED: the Owner workspace has two sections (Billing, Daily records) selected by local component state. No router, state library, or UI framework was introduced; this matches the existing single-page owner workspace and customer shell.
- IMPLEMENTED: Customer and Product names are resolved client-side from the two reference lists the owner already loads. `DailyRecordResponse` intentionally exposes only `customerId`/`productId`, so no new join or denormalized response field was added to the API.
- IMPLEMENTED: the unit-price snapshot is prefilled from the current `Product.price` but remains an owner-editable, explicitly submitted value. The backend remains authoritative; the frontend never calculates `amount` and never derives price from Subscription defaults.
- IMPLEMENTED: browser-side checks mirror only the shape the server enforces (required selections, past-or-present date, positive quantity for COLLECTED/EXTRA, zero quantity and no price for SKIPPED/PAUSED). They are usability guards, not a second rule engine.
- IMPLEMENTED: correction reuses `PUT` and is offered only for today's records, matching the existing "past daily records cannot be updated" server rule. Past records are listed as read-only.
- IMPLEMENTED: 401 clears the session through the existing callback; 403 is shown as a permission error and does not expire the session.
- IMPLEMENTED: business dates are derived from `Asia/Kolkata` in the browser, consistent with the injected business `Clock` used by the backend. INFERENCE: the browser zone is only a display/default guard; the server still evaluates `@PastOrPresent` and the business clock.

## Trade-offs
- The review list is date-anchored rather than a range, which keeps the common "what happened today/yesterday" case to one click but does not yet offer a multi-day range control. The endpoint already supports `fromDate`/`toDate` if that is added later.
- The entry form disables quantity and price for SKIPPED/PAUSED instead of hiding them, so the operator always sees all six required concepts in one place.
- A record card shows labels rather than a dense table so the workflow stays usable on small screens with the existing CSS tokens.

## Security implications
- No new route, DTO, or authorization rule was added. The workspace calls only OWNER endpoints, and `SecurityConfig` still enforces `/api/owner/**` as ROLE_OWNER. Nothing here is an authorization control; it is a UI boundary only.

## Tests
- `frontend/src/ownerDailyRecordsApi.test.ts` (5 tests): owner customer/product loading, only-active-filter query construction with page/size, create payload shape, update payload limited to quantity/status/price, and rejection of malformed payloads plus 401/403/409 handling.
- `frontend/src/ownerDailyRecords.component.test.tsx` (11 tests): customer/product loading state, chargeable creation with selected date/slot/quantity/price, quantity validation with no request sent, zero-quantity SKIPPED creation, empty state, rejected creation preserving input, rejected correction, reference-loading failure with retry, filter application, 403 visibility with 401 session expiry, same-day correction preserving the original price snapshot, and navigation to Billing, Customers, and Products invoking each section callback.
- No backend test was added: no backend behavior changed.
- Verification: `./mvnw test` = 138 tests, 0 failures, 0 errors, 0 skipped. `npm test` = 39 API tests + 17 component tests, 0 failures. `npx tsc -b`, `npm run build`, and `npm run lint` passed. `git diff --check` passed.

## Problems Encountered
- The first version nested a help span inside the price `<label>`, which polluted the control's accessible name (and broke label-based queries). The hint was moved to a sibling element referenced by `aria-describedby`.
- Record cards originally used `<div>`/`<article>`, so status labels such as "Skipped" were ambiguous with the identical `<option>` text in the status filter. The review list is now a labelled `<ul>`, which scopes both assistive-technology navigation and test queries.
- `ownerDailyRecordsApi.ts` initially imported `./authApi` without the file extension, which does not resolve under the repo's `node --experimental-strip-types` API-test runner. It now matches the other clients' `./authApi.ts` form.

## Lessons Learned
- Keeping business rules server-side made this slice a client-only change: the form submits intent, and the server decides validity, amount, snapshot, and period state.
- Accessible naming is a correctness concern in tests, not only a compliance concern; label text and list semantics are part of the component contract.

## Interview Takeaway
The Owner entry and review workflow was delivered as a pure frontend slice because the DailyRecord API already modeled actual supply correctly: identity, slot, status, price snapshot, and amount are all server-owned, and the UI only collects intent and renders stored values.

## Git Commit
Not yet committed; commit hash will be recorded after a commit exists.

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


# Development Owner Seeding

## Date
2026-10-03 (local module work)

## Git Commit
Not yet committed; commit hash will be recorded after a commit exists.

## What We Did
IMPLEMENTED: Added `config/DevOwnerSeeder`, an `ApplicationRunner` annotated `@Profile("dev")`, so a fresh local
H2 database can be signed into as OWNER without a manual SQL insert. `application-dev.properties` binds
`milkweb.dev-owner.phone` and `milkweb.dev-owner.password` to the `MILKWEB_DEV_OWNER_PHONE` and
`MILKWEB_DEV_OWNER_PASSWORD` environment variables. No new endpoint, no committed credential, and no schema
change was introduced.

## Design Decisions
- IMPLEMENTED: The bean is gated by `@Profile("dev")` rather than only by an `if` statement, so the code is not
  even instantiated outside local development.
- IMPLEMENTED: A second runtime guard requires `dev` to be the only active profile. `@Profile` matches when `dev`
  is present *alongside* other profiles, so a future `dev,prod` start would otherwise still seed. The explicit
  check removes that failure mode.
- IMPLEMENTED: Configuration is read from the `Environment` inside the single main class rather than through a
  new `@ConfigurationProperties` class. Two dev-only strings did not justify a second type, and it keeps the
  change to one file. The cost is that the keys are string literals instead of type-checked properties.
- IMPLEMENTED: The raw password is passed to `UserService.createUser(phone, rawPassword, UserRole.OWNER)` so
  BCrypt hashing stays in one place. The seeder never hashes anything itself and never logs the password.
- IMPLEMENTED: Missing or invalid configuration logs what to set and creates nothing. There is no default
  phone, no default password, and no committed demo credential.
- IMPLEMENTED: An existing OWNER is left completely untouched, and an existing non-owner is reported but never
  promoted. Silently granting OWNER to an existing account would be a privilege escalation.
- IMPLEMENTED: The password rules mirror the existing customer-provisioning contract instead of inventing new
  ones: `OwnerCreateCustomerRequest` already requires `@Size(min = 8)`, so the seeder requires the same 8
  characters and a seeded owner is held to the same minimum as an owner-created customer.
- FACT: BCrypt cannot hash more than 72 bytes. That is a separate, engine-imposed limit rather than a policy
  choice, so the seeder rejects anything longer and measures the length in UTF-8 bytes rather than Java
  characters, because a character count understates a multi-byte password.
- PLANNED / NOT YET IMPLEMENTED: a production bootstrap flow, owner password change/reset, and any CLI-based
  owner creation.

## Technical Concepts
- Spring `ApplicationRunner` and `@Profile` conditional bean registration
- `Environment.getProperty` and `getActiveProfiles` for configuration and profile checks
- BCrypt hashing through the existing `UserService`
- Spring test context caching, `@ActiveProfiles`, and `@TestPropertySource`
- `StandardEnvironment` plus `MapPropertySource` as a lightweight `Environment` stub in tests

## Tests
`DevOwnerSeederTest` (15 tests) covers profile exclusion, the single-profile guard, missing/invalid phone and
password, phone trimming, BCrypt hash verification, restart idempotency, existing-owner password preservation,
no promotion of an existing customer, and the 72-byte BCrypt limit in both ASCII and multi-byte UTF-8 form
(including a Unicode password that exceeds the limit by bytes while remaining under it by characters, and one
that stays within it). `DevOwnerSeederDevProfileTest` (1 test) proves the real wiring: with `dev` active and the
settings supplied as properties, the bean is registered and the owner already exists once the test body runs.
Verification: `./mvnw clean test` completed with 154 tests, 0 failures, 0 errors, and 0 skipped (BUILD SUCCESS).

## Risks and Follow-up
- A seeded owner is a real OWNER row in whatever database the `dev` profile points at. Today that is the
  in-memory `milkdev` H2 database, so it disappears on shutdown; pointing the `dev` profile at a persistent
  database would persist the account, and rotating the password would require deleting the row because the
  seeder intentionally never overwrites one.
- The seeder cannot help an owner who has forgotten the seeded password; password reset remains unimplemented.
- The 8-character minimum is stated twice in different places — `@Size(min = 8)` on
  `OwnerCreateCustomerRequest` and `MIN_PASSWORD_LENGTH` in the seeder — so changing one would not change the
  other. They currently agree, and neither is derived from a shared constant.

## Problems Encountered
- The first end-to-end test asserted seeding in one method and bean registration in another. Because
  `ApplicationRunner` runs once per application context, the `@AfterEach` cleanup from the first method deleted
  the owner before the second method read it. Both assertions were merged into one method.
- A `./mvnw clean test` run initially failed with `class file version 69.0` for one test class while the
  toolchain reported Java 21, echoing the earlier Java 25 incident in this log. The stale `target` output was
  the cause; `./mvnw clean` recompiled consistently and the suite passed. No source or POM change was needed.

## Lessons Learned
- A profile-scoped bean still needs its own runtime guard if the profile might ever be combined with another.
- Startup code that provisions accounts must decide explicitly what to do with existing accounts; "do nothing
  destructive" is safer than "make it match the configuration".
- A configuration value that arrives from the environment is untrusted input and needs the same validation as
  an HTTP request, even when it never leaves the developer's machine.

## Interview Takeaway
Provisioning a first privileged account is a small feature with real security weight. The design keeps the
blast radius small (dev profile only, explicit env vars, no committed defaults, no silent promotion) and proves
each rule with a test rather than a comment. The same shape — conditional bean, validated input, idempotent
behavior — is what a production bootstrap command would reuse.

## Milestone: Customer self-registration (2026-10-15)

Commit: not yet committed (fill in after commit).

### What was implemented
- IMPLEMENTED: `POST /api/auth/register` (`AuthController.register`, `AuthService.register`) returns `201`
  with the login response body, so the new customer is signed in immediately.
- IMPLEMENTED: `RegisterCustomerRequest` (`phone`, `password`, `name`, optional `address`). It has no role, ID,
  or account-state fields.
- IMPLEMENTED: `CustomerStatus.PENDING`. Self-registered customers start PENDING; the owner approves them by
  setting ACTIVE (the existing update endpoint; the owner Customers screen shows an "Approve" button).
- IMPLEMENTED: `CustomerService.registerCustomer` shares a private creation method with the owner path. The
  insert is flushed and a `uk_users_phone` violation becomes `PhoneAlreadyRegisteredException` (409).
- IMPLEMENTED: `SecurityConfig` permits `POST /api/auth/register`.
- IMPLEMENTED (frontend): `registerCustomer` and `registrationProblem` in `authApi.ts`, the
  `CustomerRegistration` form, a "Create an account" / "Sign in" switch on the sign-in screen, a pending
  approval notice in the customer workspace, and PENDING support in every status type and response check.
- No database migration: `customers.status` is `VARCHAR(20)` without a check constraint.

### Why
Customers could not sign up themselves; only the owner could create accounts. See ADR-012.

### Problems found and solved
- The frontend response checks in `ownerCustomersApi.ts`, `customerApi.ts`, and `ownerDailyRecordsApi.ts`
  rejected unknown statuses. Adding PENDING on the backend alone would have broken the owner's customer list
  and daily-records screen as soon as one customer registered. All three were updated together.
- The owner create path checked for duplicates and then inserted, but the insert only reached the database at
  commit time. Two concurrent creates for one phone could produce a 500. The shared creation method now
  flushes and translates the constraint violation. This was verified by temporarily removing both pre-checks
  and running the concurrency test (1 success, 3 clean conflicts).
- FOUND, NOT FIXED: the `dev` profile does not disable Flyway, so `./mvnw spring-boot:run` with `dev` fails on
  H2 (`Unknown data type: "TIMESTAMPTZ"` in `V1__baseline_schema.sql`). Workaround: add
  `--spring.flyway.enabled=false`. This predates this milestone.

### Security
Role fixed server-side; client role, ID, and status are ignored. Duplicate and concurrent duplicate phones
give 409. The owner phone cannot be taken over. Passwords are 8 characters to 72 bytes, BCrypt-hashed, and
never returned. Unverified phones and the lack of rate limiting are documented risks (ADR-012).

### Tests
- Backend: `RegistrationControllerTest` (9) and `CustomerRegistrationConcurrencyTest` (1).
  `./mvnw package`: 196 tests, 0 failures, 0 errors, 0 skipped.
- Frontend: registration API tests (5), PENDING response tests (2), and
  `customerRegistration.component.test.tsx` (7). `npm test`: 76 API tests + 65 component tests, all passing.
  `npm run lint` clean; `npm run build` succeeds.
- Manual HTTP check against the packaged jar (dev profile, H2, Flyway disabled): CORS preflight, register
  with a smuggled OWNER role (stored as CUSTOMER/PENDING), `/api/customer/me`, 403 on owner endpoints,
  duplicate and owner-phone 409s, invalid 400, customer login, owner list and approval.

### Lessons learned
- A new enum value is an API change. Every client-side check that lists the allowed values must change at the
  same time.
- "Check, then insert" is not enough under concurrency; the unique constraint is the real guarantee and its
  error needs to be translated.

### Interview takeaway
Public sign-up is a privilege boundary: fix the role on the server, let the database enforce uniqueness, and
limit what an unverified account can do until a trusted person approves it.

## Milestone: Login and registration rate limiting (2026-10-15)

Commit: not yet committed (fill in after commit).

- IMPLEMENTED: `AuthRateLimiter` (fixed-window counters, in memory, capped at 50,000 keys),
  `RateLimitProperties` (`milkweb.rate-limit.*`), `TooManyAttemptsException` (429 + `Retry-After`), and
  `ClientIpResolver` (first `X-Forwarded-For` entry, else the remote address). Wired into
  `AuthService.login` and `AuthService.register`.
- Why: unlimited password guessing was possible on `/api/auth/login`, and public registration added a second
  unauthenticated write endpoint. See ADR-012, "Rate limiting".
- Decision: the "already registered" message stays, because a clear message matters more for customers of all
  ages than hiding which numbers have accounts.
- Alternatives: Bucket4j or a Redis-backed limiter (a new dependency or service, unnecessary for one instance);
  a per-IP limit only (Render's `X-Forwarded-For` may be forgeable, so it cannot be the only protection).
- Problems: (1) `Retry-After` was truncated to 899 seconds under a real clock; it now rounds up so clients
  never retry early. (2) A new test context with different properties shared the `milktest` H2 database, and
  its shutdown dropped tables used by other tests (93 errors). It now uses its own database URL, matching the
  existing `CustomerServiceTest` pattern.
- Tests: `AuthRateLimiterTest` (6), `AuthRateLimitTest` (3), and two frontend 429 tests. Backend 205/205,
  frontend 78 + 65, lint clean, build succeeds. Manual HTTP check against the jar: 6th wrong password returns
  429 with `Retry-After: 900` and the correct password is also refused, other accounts are unaffected, and the
  11th registration from one IP returns 429.
- Interview takeaway: limit by the thing an attacker cannot change (the target account) and treat
  proxy-supplied IPs as hints. Bound in-memory state so the limiter cannot itself become a denial-of-service
  vector.

## Milestone: Redesign phase 1, Radhika Farms look (2026-10-15)

Commit: not yet committed (fill in after commit).

- IMPLEMENTED: new design tokens and fonts (`index.css`), a full rewrite of `App.css` on those tokens, and a new
  public sign-in page (cocoa hero with milk-can illustration, product marquee, sign-in / create-account tabs,
  farm story and product lines). See `docs/design-system.md`.
- IMPLEMENTED: dark mode that follows the device setting. The four time-of-day themes, `TimeTheme.ts`, and
  `MilkCanMascot.tsx` were removed at the owner's request.
- IMPLEMENTED: the workspace sidebar became a cocoa header band using CSS only, so the five screen components
  kept their markup and behaviour. User-facing text now says "Radhika Farms" instead of "Milk Web".
- Why: the owner chose a dairy-flavoured version of a farm-label style after reviewing an interactive mockup.
  Text sizes rose from 9–12px to a 17px base with large tap targets, for customers of all ages.
- Deferred: showing ₹ on every amount changes formatters that tests assert exactly; it moves to phase 3
  (receipt-style bills).
- Problems: on phones the "Generate a draft bill" customer dropdown was squeezed by its button; the form now
  stacks below 640px.
- Tests: no behaviour changed. `npm test` 78 API + 65 component tests pass; lint clean; build succeeds. Visual
  check: the app was run locally with seeded data and screenshotted in headless Chrome at 1280px and 390px in
  light and dark mode (sign-in, sign-up, owner billing, daily records, customers, customer overview, bills,
  pending account). No page scrolls sideways.

## Milestone: Redesign phase 2, collection calendar and month summary (2026-10-15)

Commit: not yet committed (fill in after commit).

- IMPLEMENTED (customer Overview): `CustomerMonth.tsx` shows the month at a glance (litres collected,
  collections, amount so far or month total, and the latest issued bill with a link to Bills), a Monday-first
  collection calendar with one mark per record (collected, extra, skipped, paused), and the details of a tapped
  day. ‹ › arrows move between months, from the month the customer joined up to the current month. Future days
  cannot be chosen.
- IMPLEMENTED (owner Daily records): ‹ › arrows step the review list one day at a time, keeping the other
  filters; the next arrow stops at today.
- IMPLEMENTED: `monthCalendar.ts` holds the pure logic (month arithmetic, month date range, totals, calendar
  cells) and `getCustomerMonthRecords`, which follows every page because the API returns at most 100 records per
  request and a month can hold more (2 products x 2 slots x 31 days = 124).
- No backend change: the existing customer daily-records and bills endpoints already provide the data, still
  scoped to the signed-in customer.
- Wording: the summary says "Amount so far" and "Latest bill", not "outstanding". Payments are not recorded yet,
  so an unpaid balance cannot be shown truthfully (phase 4).
- Fixed: a phase 1 style made the Morning/Evening radio buttons on the owner entry form full width and 48px
  tall. They are now normal 20px controls.
- Problems: `customerMonth.ts` and `CustomerMonth.tsx` collided on the case-insensitive macOS file system; the
  logic file is named `monthCalendar.ts`.
- Tests: `monthCalendar.test.ts` (9: month arithmetic across years and leap years, range up to today, every page
  requested with no customer ID, totals that ignore skipped/paused and separate litres from kilograms, rounding,
  Monday-first cells, slot order) and `customerMonth.component.test.tsx` (7: summary, day details, future days
  disabled, month navigation limits, latest-bill link, no-bill message, error retry and 401 expiry), plus one
  owner test for the day arrows. `npm test` 87 API + 73 component tests pass; lint clean; build succeeds.
  Screenshots with seeded data checked at 1280px and 390px in light and dark mode.

## Milestone: Redesign phase 3, receipt-style bills and rupee amounts (2026-10-15)

Commit: not yet committed (fill in after commit).

- IMPLEMENTED: `BillReceipt.tsx`, one receipt component for both customer and owner bills: farm header, bill
  number, customer details, totals per product and price, bill total, an "Issued <date>" or "Draft · not issued"
  stamp, and the full day-by-day lines on request. `receiptLines.ts` groups lines by product and unit price, so a
  mid-month price change appears as its own line and every line reads "quantity × one price".
- IMPLEMENTED (customer Bills): the selected bill as a receipt beside a list of all bills; ‹ › arrows step to the
  older or newer bill and load the next or previous page when they reach the end of the current one.
- IMPLEMENTED (owner Billing): the bill snapshot panel now uses the same receipt.
- IMPLEMENTED: `money.ts` `formatRupees` (₹ with Indian digit grouping, e.g. ₹1,23,456.78) used for every amount
  and price in the app.
- Backend: `GET /api/customer/bills` now sorts by billing month (`periodStart DESC`, then `createdAt DESC`, `id`)
  instead of creation time, so the arrows never jump out of month order when an older bill is generated late.
  No schema change.
- Not included: paid/unpaid stamps and balances need the payments ledger (phase 4). Receipts say "This bill is
  not a payment receipt".
- Problems: `billReceipt.ts` and `BillReceipt.tsx` collided on the case-insensitive macOS file system again; the
  logic file is `receiptLines.ts`.
- Tests: `receiptLines.test.ts` (5: rupee format with Indian grouping, per-product totals, price-change split,
  rounding, empty bill); three new customer bill tests (receipt contents, arrows and list within a page, arrows
  loading another page); a backend test that bills generated out of order come back in month order. Exact amount
  assertions in five existing tests were updated to the ₹ format. Frontend 92 API + 76 component tests pass;
  backend 206 tests pass; lint clean; build succeeds. Screenshots with seeded data checked at laptop and phone
  sizes in light and dark mode.

## Milestone: Payment ledger backend (2026-10-15)

Commit: not yet committed (fill in after commit).

- IMPLEMENTED: Flyway `V4__create_payment_ledger.sql` (new tables only) and the `ledger` package: entities,
  repositories, `LedgerService`, owner and customer controllers, exception handler. Bill issuing now applies any
  held credit to the new bill. See ADR-013 and the API table in `api-design.md`.
- Owner rules implemented exactly as approved: oldest bill first, leftover kept as credit and applied to the next
  bill automatically, refunds limited to credit, append-only corrections with reasons, INR, Cash/UPI.
- Found and fixed before release: the CORS allow-list did not include `Idempotency-Key`, so the browser would have
  blocked every payment request in production.
- Verification: the concurrency test fails when the customer lock is removed (money applied twice), proving the
  lock is what protects balances. PostgreSQL 16 test applies V1-V4 and Hibernate validation passes.
- Problems: Testcontainers 1.19.8 cannot talk to Docker Desktop 29 by default (old API version, socket path);
  environment variables documented in `testing.md` work around it without changing dependencies.
- Interview takeaway: immutable money facts plus explicit allocations, derived balances, per-account locking, and
  idempotency keys for retries.

## Milestone: Payment screens (2026-10-15)

Commit: not yet committed (fill in after commit).

- IMPLEMENTED (owner Billing): month totals (billed, received, still owed), Unpaid / Part paid / Paid filters,
  a Payment column with status and amount due, and a "Customers with balances" table. Opening an issued bill
  shows the receipt with payments and balance next to `OwnerBillPayments.tsx`: record payment (live preview of
  which bills it clears and what becomes credit), adjust this bill (credit or extra charge with a reason),
  return credit (only when credit exists), and history with "Correct this payment".
- IMPLEMENTED (customer): Overview shows Balance due (or Credit) with a link to the latest bill; Bills shows
  each receipt's payments, balance and a Paid in full stamp, payment status in the bill list, and a Payments
  history. If the account cannot load, bills still show without any payment claim.
- `ledgerApi.ts`: typed client, response checks, and `previewPayment`, which uses the server's oldest-first rule
  in paise so the preview matches exactly. Each save sends an `Idempotency-Key`; the key is kept after a failed
  save and replaced after a successful one, so a retry after a network error cannot record money twice.
- Owner bills page size raised from 10 to 50 so the filters see a whole month on one page.
- Tests: `ledgerApi.test.ts` (9), `ownerBillPayments.component.test.tsx` (5), two customer overview balance
  tests, one receipt payment-status test. Frontend 101 API + 84 component tests pass; lint clean; build
  succeeds. End-to-end against the local backend: 10 scripted checks pass (overpayment to credit, repeated key,
  part payment, ₹30 credit, refund limit, customer sees only own account without owner notes, customer cannot
  record payments). Screenshots checked at laptop and phone sizes, light and dark.
- Known limit: on phones the Payment column is reached by scrolling the bills table sideways.
- Rollback note: once V4 has run, older code refuses to start because Flyway sees an applied migration it does
  not know. To roll back, deploy the older commit with `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:missing` set
  on Render; the new tables are simply ignored.

# End of development log (most recent milestones recorded)

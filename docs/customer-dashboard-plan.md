# Customer Dashboard: Backend and API Gap Analysis

## Scope and current baseline

This document began as a planning gap analysis. **Phases 1-4 are implemented:** customer authentication/shell/profile, customer DailyRecord history/today's collection, read-only customer subscriptions, and issued customer bills. The backend uses Spring MVC controllers, service-layer business rules, Spring Data JPA repositories, DTOs at the HTTP boundary, and stateless JWT authentication. `SecurityConfig` restricts `/api/owner/**` to `OWNER` and `/api/customer/**` to `CUSTOMER`.

The React app routes to the owner billing workspace or customer shell based only on the role returned by `/api/auth/me`. The customer workspace uses `GET /api/customer/me` for profile, `GET /api/customer/daily-records` for today's collection and paged history, `GET /api/customer/subscriptions` for subscriptions, and `GET /api/customer/bills` for paged issued bills and line summaries. Do not expose owner endpoints to customers or treat frontend role checks as authorization.

## Existing dashboard-relevant API

| Capability | Existing endpoint and contract | Authorization | Dashboard gap |
|---|---|---|---|
| Login/session | `POST /api/auth/login` accepts `{ phone, password }`, returns `{ token, userId, phone, role }`. `GET /api/auth/me` returns `{ userId, phone, role }`. | Login is public; `/me` requires authentication. | Both roles share login and restoration; the validated role selects the corresponding protected workspace. |
| Customer profile | `GET /api/customer/me` returns `CustomerResponse { id, userId, phone, name, address, billingType, status, joinDate }`. The controller gets `userId` from the authenticated principal and looks up the linked Customer. | CUSTOMER role. | Read-only profile is available. There is no customer profile update endpoint. |
| Owner customer management | `GET /api/owner/customers`, `GET /api/owner/customers/{customerId}`, `PUT /api/owner/customers/{customerId}`. Responses use `CustomerResponse`; update accepts name, address, billing type, status, and join date. | OWNER role. | Not suitable for customer use: the owner list and ID-based operations must remain unavailable to CUSTOMER. |
| Product catalog | `GET /api/owner/products` returns a list of `ProductResponse { id, name, description, unit, price, status, createdAt, updatedAt }`; detail is `GET /api/owner/products/{productId}`. | OWNER role. | No customer catalog or product detail endpoint. Product has generic name/unit fields, not a cow/buffalo type field. |
| Subscriptions | Owner APIs: `POST /api/owner/subscriptions`, `GET /api/owner/subscriptions` (optional `customerId`, `productId`, `status`, `effectiveOn` filters), `GET /api/owner/subscriptions/{subscriptionId}`, and `PUT /api/owner/subscriptions/{subscriptionId}`. Customer API: `GET /api/customer/subscriptions` returns a customer-scoped list with product name/unit, quantity, status, dates, `effectiveToday`, and date-only `periodState`. | Owner management is OWNER-only; customer list is CUSTOMER-only and derives identity from the authenticated principal. | Read-only list is implemented; customer mutations are intentionally absent. |
| Daily records | Owner APIs: `POST /api/owner/daily-records`, `GET /api/owner/daily-records` (customer/product/date/range/slot/status filters and pagination), `GET /api/owner/daily-records/{dailyRecordId}`, and `PUT /api/owner/daily-records/{dailyRecordId}`. Customer API: `GET /api/customer/daily-records` supports date or bounded range, slot/status, and pagination; returns `CustomerDailyRecordResponse` with product labels and actual record values. | Owner APIs require OWNER. Customer API requires CUSTOMER and derives Customer from authenticated user; customerId filters are rejected. | Customer today/history views and the Owner DailyRecord entry/review workspace are implemented. The owner review list is date-anchored and does not yet expose a multi-day range control. Missing slot row remains distinct from explicit SKIPPED/PAUSED record. |
| Billing preference | `CustomerResponse.billingType` uses `PREPAID` or `MONTHLY`. Monthly bill generation is limited to MONTHLY customers. | Profile is CUSTOMER-readable; billing operations are OWNER-only. | There is no `DAILY` billing type. PREPAID is a stored preference, not a payment ledger or completed prepaid-balance workflow. |
| Bills | Owner APIs: `GET /api/owner/bills` (optional `customerId`, `period`, `page`, `size`), `GET /api/owner/bills/{billId}`, and `POST /api/owner/billing-periods/{period}/bills/{customerId}`. Customer API: `GET /api/customer/bills` returns paginated ISSUED bills with period, issue timestamp, total, status, and line summaries. | Owner operations are OWNER-only; customer list is CUSTOMER-only, resolves ownership from the principal, and rejects `customerId` input. | Issued bill list and expandable details are implemented; DRAFT bills are excluded. |
| Payments/balance | No Payment or BalanceAdjustment entity, repository, service, controller, or endpoint exists. Bills store a total and DRAFT/ISSUED status; they do not store payments or balances. | Not applicable. | Payment history and outstanding balance cannot be truthfully displayed yet. |

## Feature-by-feature assessment

### Profile and account

The customer can read their own profile through `/api/customer/me`, including their current billing preference and account status. The implementation resolves the Customer via the authenticated User ID, not a client-supplied customer ID. No customer profile or credential update API exists.

### Today's collection and history

Daily records already represent actual outcomes, with date, slot, quantity, status, amount, and historical unit-price snapshot. The supported slots are MORNING and EVENING; SKIPPED and PAUSED are persisted as zero-quantity, non-chargeable records, and EXTRA is chargeable. Owners manage records through owner endpoints, while customers can read their own records through the customer-scoped, read-only API.

`GET /api/customer/daily-records` now provides scoped history and supports a specific date or an inclusive bounded range, slot/status filters, and pagination. The Overview queries the current India-local date and renders separate MORNING/EVENING outcomes from persisted records. A missing row is presented as “No record available for this slot”; it is not treated as an explicit SKIPPED/PAUSED record.

### Products and subscriptions

Subscriptions store a default quantity and effective dates; they do not prove actual supply. `GET /api/customer/subscriptions` returns the customer's product name/unit alongside quantity, status, and dates without exposing owner management operations. `effectiveToday` requires ACTIVE status and the inclusive date range to contain the current India business date. `periodState` separately describes date position (UPCOMING, IN_PERIOD, ENDED), so status alone is never used to claim current effectiveness. Current product price is not returned; actual collection and historical prices belong to DailyRecords.

### Billing and balances

The current billing choices are PREPAID and MONTHLY; DAILY is not an implemented enum or workflow. Closed-period monthly bills are generated from COLLECTED/EXTRA DailyRecords, with quantities and price snapshots copied into immutable bill lines. Bills transition from DRAFT to ISSUED through an explicit owner issue action. `GET /api/customer/bills` exposes only ISSUED bills with issue metadata; a closed period or generated DRAFT alone is not customer-visible. Line summaries use the existing immutable bill snapshots.

No payment state is modeled. An issued bill must not be presented as paid or unpaid, and its total is not a verified outstanding balance.

Customer bill access is scoped to the authenticated customer and limited to ISSUED bills with issue metadata; a closed period or generated DRAFT is not customer-visible. This reuses the existing status and database constraint rather than introducing a new lifecycle rule. An outstanding balance cannot be calculated from the present model: no payment allocation, credits/debits, or adjustment ledger exists. Summing issued bills would be billed-to-date, not a verified outstanding balance, and must not be labeled as one.

### Payment and balance domain - DESIGN COMPLETE, NOT IMPLEMENTED

Phase 5 documents a proposed customer-account model with immutable OWNER-attributed receipts, explicit append-only allocations to issued Bills, separate non-cash adjustments, and derived bill/account summaries. This supports both a bill-centric "record payment" workflow and advance/excess receipts as unallocated customer credit. Customer financial endpoints, frontend payment history, and balance screens remain absent; no balance may be inferred from current bills.

The recommendation is explicitly a proposal pending business confirmation. Currency and legacy Bill interpretation, PREPAID semantics, allocation automation, receipt corrections/refunds, adjustment approval, and issued-bill correction/void policy remain unresolved. See [Payment and Balance Domain Design](./payment-domain-design.md) and [ADR-010](./decisions/ADR-010-customer-financial-ledger.md).

## Minimal API changes recommended

1. **Customer daily records - IMPLEMENTED:** The read-only endpoint supports `recordDate` or a bounded `fromDate`/`toDate`, optional `slot` and `status`, and bounded pagination. It derives Customer from the authenticated User ID, rejects a supplied customer ID, and returns a customer-facing DTO with product name/unit.
2. **Customer subscriptions - IMPLEMENTED:** The read-only list is scoped to the authenticated Customer and returns all statuses/date periods with product summary. Current effectiveness is derived using existing ACTIVE-plus-inclusive-date semantics; no subscription eligibility/overlap rules were duplicated.
3. **Customer bills - IMPLEMENTED:** A bounded paginated `/api/customer/bills` list returns only the authenticated Customer's ISSUED bills and immutable line summaries. The frontend expands line details without adding a separate customer-supplied bill-ID route.
4. **Overview summary - IMPLEMENTED:** The current-day view derives morning/evening status from persisted DailyRecords; missing slots remain distinct from explicit no-supply records. Add a summary endpoint only if measured response/query needs justify it.
5. **Payment and balance design - COMPLETE (PROPOSED):** Domain alternatives, account-credit recommendation, proposed API contracts, currency/precision, security, concurrency, test invariants, and owner decisions are documented. This is not implementation approval.
6. **Payment and balance implementation - BLOCKED ON OWNER DECISIONS:** Confirm currency and legacy Bill treatment, account-credit/PREPAID policy, allocation rules, receipt corrections/refunds, bill corrections, and adjustments. Select a production migration strategy before schema changes. Do not infer a balance from Bills alone.
7. **Profile edits/catalog browsing:** No new endpoint is required for the initial read-only profile. Add a customer edit or product-browsing endpoint only after the required customer actions and exposed fields are agreed.

All customer endpoints should use `/api/customer/**` and remain CUSTOMER-role protected. Object IDs in paths (for example, bill detail) still require an ownership predicate in the service/repository query; authentication by role alone is insufficient. Return 404 for a resource outside the caller's scope if that matches the API's chosen non-disclosure convention. Keep owner workflows under `/api/owner/**`.

## Implemented frontend workspace

The shared API/auth layer remains in `frontend/src/authApi.ts`; `frontend/src/App.tsx` selects the owner or customer workspace only after validating `/api/auth/me`. The customer shell in `frontend/src/CustomerWorkspace.tsx` provides:

- **Overview:** today's morning/evening outcomes from persisted DailyRecords. Missing slots are not represented as confirmed skipped records.
- **Records:** bounded date-range history with slot/status filters and pagination.
- **Subscriptions:** read-only subscription groups with product, default quantity, status, and effective-date information.
- **Bills:** paginated issued bills with expandable line summaries and explicit messaging that issue status is not payment status.
- **Profile:** authenticated account details, read-only.

The workspace uses shared customer API clients, bearer-token handling, and user-visible loading, empty, error, and retry states. Customer API requests do not use a client-supplied customer ID for authorization. Backend role checks and customer scoping remain authoritative.

The owner workspace in `frontend/src/App.tsx` has two sections. Billing is implemented. **Daily records is IMPLEMENTED** (`frontend/src/OwnerDailyRecords.tsx`): the owner selects customer, product, date, MORNING/EVENING, status, and actual quantity, saves the record, and reviews it with date/customer/product/slot/status filters and pagination. It calls the existing OWNER DailyRecord endpoints unchanged and computes nothing itself; amount, snapshot immutability, duplicates, eligibility, and BillingPeriod state remain server decisions. Same-day correction reuses `PUT`; past records are listed read-only, matching the existing backend rule.

## Assumptions, decisions, and trade-offs

- Customer profile identity is the authenticated User linked to exactly one Customer (the `customers.user_id` column is unique).
- Cow/buffalo distinction is currently represented, if at all, by product catalog names; there is no dedicated animal-type field.
- The current enum's PREPAID is not evidence of stored customer credit or payment status.
- A missing DailyRecord must not be presented as a confirmed skipped collection.
- Customer-visible bills are limited to ISSUED bills with issue metadata, reusing the existing bill lifecycle; DRAFT bills are excluded.
- Read-only dashboard APIs are the smallest safe first step. Mutations (skip requests, subscription changes, payments) require separate business rules and authorization decisions.
- Existing DTOs and services should be reused where appropriate, but customer-facing DTOs/queries must not broaden access to owner-only resources.

## Phased implementation sequence

1. **Customer authentication and shell - COMPLETE:** shared role-aware login/session validation, protected workspace, accessible navigation and placeholders, profile view.
2. **Records and today overview - COMPLETE:** customer-scoped paged endpoint, product labels, date/range filters, status semantics, and morning/evening view.
3. **Subscriptions and product summary - COMPLETE:** customer-scoped read-only API and responsive subscription view, separating status from date-period effectiveness.
4. **Issued bills - COMPLETE:** customer-scoped paginated endpoint and expandable read-only bill/line summaries. DRAFTs are excluded; ISSUED does not imply paid.
5. **Payment and balance design - COMPLETE (PROPOSED):** Define account-credit behavior, a receipt/allocation/adjustment model, API contracts, security, concurrency, precision, and test invariants. This documents a recommendation, not owner approval.
6. **Payment and balance implementation - BLOCKED ON OWNER DECISIONS:** Confirm currency and legacy Bill interpretation, PREPAID/account-credit behavior, allocation policy, correction/refund and bill-void rules, adjustment approvals, and migration strategy before implementation.

Each phase should add focused cross-customer isolation tests (including attempts to use another customer's IDs), role tests, DTO contract tests, and frontend loading/empty/error tests. Use isolated synthetic test data. Do not proceed from one phase to the next without review.

## Current limitations

- Customer login, profile, actual DailyRecord views, and the read-only subscription view are implemented.
- Customer-facing bills are available only after explicit issue. Payment history and balance endpoints remain absent.
- Payment, BalanceAdjustment, credit/debit allocation, and outstanding balance are absent; Phase 5 is a design proposal only.
- DAILY billing is not supported; billing type values are PREPAID and MONTHLY.
- Customer-visible bills are limited to explicitly issued bills; profile-edit policy needs product confirmation.
- Product has no explicit cow/buffalo classification and no customer-facing catalog endpoint.
- Phase 3/4 verification uses focused/full backend tests, frontend API/view-model tests, production build, lint, and diff checks. No live browser-to-backend workflow is claimed for subscriptions or bills. Phase 5 changed documentation only and did not run application tests.

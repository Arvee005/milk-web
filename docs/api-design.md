# API Design

This document records the API endpoints implemented in the project and their request/response shapes, validation, and security.

Existing endpoints (verified in code)

1) POST /api/auth/login
- Controller: AuthController.login
- Request: LoginRequest { phone: 10-digit string, password }
- Response: AuthService.LoginResponse { token, userId, phone, role }
- Validation: phone 10-digit pattern, password not blank
- Security: public (permitAll)
- Errors: 401 Unauthorized for bad credentials; 429 Too Many Requests with `Retry-After` after 5 failed logins for one phone or 30 from one client IP within 15 minutes

1b) POST /api/auth/register (IMPLEMENTED; see ADR-012)
- Controller: AuthController.register -> AuthService.register -> CustomerService.registerCustomer
- Request: RegisterCustomerRequest { phone: 10-digit string, password: 8 chars to 72 UTF-8 bytes, name: not blank, max 100, address?: max 500 }
- No role, id, userId, status, billingType, or joinDate field. Unknown JSON fields are ignored, so a supplied `role` has no effect.
- Server-set values: User role CUSTOMER; Customer status PENDING, billingType MONTHLY, joinDate = today (Asia/Kolkata); name and address trimmed, blank address stored as null.
- Response: 201 Created with AuthService.LoginResponse { token, userId, phone, role: "CUSTOMER" } (the customer is signed in immediately)
- Security: public (permitAll, POST only)
- Errors: 400 `{ "error": "<field>: <message>" }` for invalid input; 409 `{ "error": "Phone number already registered: <phone>" }`, including concurrent duplicate requests; 429 with `Retry-After` after 10 attempts from one client IP within an hour
- A PENDING customer can use `/api/customer/**` for their own data. Owner subscription and daily-record creation return 409 until the owner sets the customer ACTIVE through `PUT /api/owner/customers/{id}`.

2) GET /api/auth/me
- Controller: AuthController.me
- Returns: current authenticated user info (userId, phone, role)
- Security: authenticated
- Browser owner flow sends the JWT in the Authorization Bearer header and verifies this response before rendering the owner workspace.

Frontend authentication behavior
- The shared frontend accepts OWNER and CUSTOMER credentials through `POST /api/auth/login`, then calls `GET /api/auth/me` before routing to either workspace. The login response role is not used to authorize or select the workspace.
- The owner workspace is rendered only for a validated OWNER. The customer shell is rendered only for a validated CUSTOMER and uses `GET /api/customer/me` for the profile view.
- The browser reports 401 as invalid login credentials during sign-in or an expired session for later API requests. It reports 403 as an authorization failure and does not treat it as an expired identity.
- These client messages do not change backend authorization: OWNER/CUSTOMER role checks remain enforced by Spring Security.

3) GET /api/auth/owner-check
- Controller: AuthController.ownerCheck
- Security: ROLE_OWNER required (PreAuthorize hasRole('OWNER'))

4) GET /api/auth/customer-check
- Controller: AuthController.customerCheck
- Security: ROLE_CUSTOMER required

5) POST /api/owner/customers
- Controller: CustomerController.createCustomer (added in milestone 5b3c11e)
- Authorization: ROLE_OWNER required (path under /api/owner/** enforced by SecurityConfig)
- Request DTO: OwnerCreateCustomerRequest
  - phone: String (10-digit), password: String (min 8), name: String (not blank), address: String, billingType, status, joinDate
- Response DTO: CustomerResponse { id, userId, phone, name, address, billingType, status, joinDate }
- Behavior:
  - Service checks for existing User by phone; if present -> 409 Conflict
  - Creates User via UserService.createUser(phone, password, UserRole.CUSTOMER)
  - Creates Customer linked to User inside a transaction
  - Returns 201 Created with Location header /api/owner/customers/{id}
- Errors: 400 validation, 409 duplicate phone, 403 forbidden if not OWNER

6) GET /api/customer/me
- Controller: CustomerController.me
- Authorization: ROLE_CUSTOMER required (path under /api/customer/**)
- Behavior:
  - Uses @AuthenticationPrincipal MilkWebUserDetails to get userId
  - Service finds Customer by userId; if not found -> 404 Not Found
  - Returns CustomerResponse
- Errors: 401 unauthorized if no token, 404 if no Customer record for user

Customer-facing DailyRecord history, subscriptions, and issued bills are implemented at `GET /api/customer/daily-records`, `/api/customer/subscriptions`, and `/api/customer/bills`. Payment history and balance APIs are not implemented; owner APIs below remain OWNER-only and must not be reused as customer endpoints. See [Customer Dashboard: Backend and API Gap Analysis](./customer-dashboard-plan.md) for current coverage and remaining work.

## Customer DailyRecord History (IMPLEMENTED)

`GET /api/customer/daily-records`
- Authorization: CUSTOMER role under `/api/customer/**`; unauthenticated requests receive 401 and non-CUSTOMER roles receive 403.
- Identity/data isolation: the controller takes the user ID from `@AuthenticationPrincipal`, resolves the linked Customer by `user_id`, and invokes a repository query with that mandatory customer ID predicate. A caller-supplied `customerId` query parameter is rejected with 400; it is never used to select records.
- Optional filters:
  - `recordDate=YYYY-MM-DD`, or
  - both `fromDate=YYYY-MM-DD` and `toDate=YYYY-MM-DD`, inclusive, with a maximum span of 366 calendar dates,
  - optional `slot=MORNING|EVENING`,
  - optional `status=COLLECTED|EXTRA|SKIPPED|PAUSED`.
- A specific date cannot be combined with range filters. A range requires both dates and `fromDate <= toDate`. Invalid date, slot, or status values return 400.
- Pagination: `page` defaults to 0 and must be nonnegative; `size` defaults to 50 and is limited to 1-100. Results are ordered by `recordDate DESC`, `slot ASC`, then `id ASC`. The query eagerly loads Product for response labels.
- Response: stable `CustomerDailyRecordPageResponse` containing `content`, `number`, `size`, `totalElements`, and `totalPages`. Each item is a `CustomerDailyRecordResponse` shaped as `{ id, recordDate, slot, productName, productUnit, quantity, unitPriceSnapshot, amount, status }`. It does not expose Customer IDs or JPA entity relationships.
- `unitPriceSnapshot` and `amount` are the persisted historical values. `productName` and `productUnit` come from the linked current Product because DailyRecord does not snapshot those labels; later owner catalog label edits can change the label shown for historical records.
- This is read-only. DailyRecord status/amount rules and historical price snapshots remain those of the existing owner-managed DailyRecord model.
- The frontend overview requests today's records; absence of a slot row is rendered as “No record available for this slot,” not as SKIPPED. The Records view submits an inclusive date range with optional slot/status filters and bounded pagination.

7) GET /api/owner/customers/{customerId}
- Controller: CustomerController.getCustomerById (implemented in milestone 4A)
- Authorization: ROLE_OWNER required (path under /api/owner/**)
- Path variable: customerId (UUID)
- Behavior:
  - Uses CustomerService.getById(customerId)
  - Finds Customer by primary-key ID with repository lookup
  - Returns CustomerResponse
- Errors: 401 unauthorized if no token, 403 forbidden for CUSTOMER or other non-OWNER users, 404 if the customer ID does not exist
- Security: OWNER-only access; the endpoint does not trust a customer-supplied identity and instead uses the path ID directly

8) GET /api/owner/customers
- Controller: CustomerController.listCustomers (implemented in milestone 4B)
- Authorization: ROLE_OWNER required (path under /api/owner/**)
- Behavior:
  - Controller delegates to CustomerService.listAll()
  - Service calls CustomerRepository.findAll()
  - Controller maps each Customer to CustomerResponse and returns a JSON array
  - If no customers exist, returns an empty array [] (HTTP 200)
- Response type: List<CustomerResponse>
- Errors: 401 unauthorized if no token, 403 forbidden for CUSTOMER or other non-OWNER users
- Notes: This first-version intentionally does not implement pagination; it is suitable for small datasets. If dataset grows, switch to Page/Pageable and/or add fetch-join to avoid N+1 queries.

9) PUT /api/owner/customers/{customerId}
- Controller: CustomerController.updateCustomer
- Authorization: ROLE_OWNER required (path under /api/owner/**)
- Request DTO: OwnerUpdateCustomerRequest
  - name: required, non-blank
  - address: optional
  - billingType: required BillingType
  - status: required CustomerStatus
  - joinDate: required LocalDate
- Editable fields: name, address, billingType, status, joinDate
- Phone is not editable through this endpoint because it is also the User authentication/login identifier. A dedicated phone/account-identifier flow is deferred.
- Response DTO: CustomerResponse
- Behavior: Updates only the customer profile fields above and returns HTTP 200.
- Errors: 400 validation or malformed input, 401 unauthorized, 403 forbidden for non-OWNER users, 404 if the customer ID does not exist

## Product Management

Product endpoints are OWNER-only under the existing `/api/owner/**` security rule. All successful responses use `ProductResponse`; JPA entities are not returned.

1) POST /api/owner/products
- Request DTO: `OwnerCreateProductRequest` (`name`, optional `description`, `unit`, `price`)
- New products default to `ACTIVE`.
- Success: 201 Created with `ProductResponse` and a Location header.
- Errors: 400 invalid request, 401 unauthenticated, 403 non-OWNER, 409 duplicate product name.

2) GET /api/owner/products
- Returns all products, including ACTIVE and INACTIVE, as `List<ProductResponse>`.
- Success: 200 OK; errors include 401 unauthenticated and 403 non-OWNER.

3) GET /api/owner/products/{productId}
- Path variable: product UUID.
- Success: 200 OK with `ProductResponse`.
- Errors: 400 malformed UUID, 401 unauthenticated, 403 non-OWNER, 404 unknown product.

4) PUT /api/owner/products/{productId}
- Request DTO: complete `OwnerUpdateProductRequest` (`name`, optional `description`, `unit`, `price`, `status`).
- Updates catalog values and status; ID and timestamps are server-managed.
- Success: 200 OK with `ProductResponse`.
- Errors: 400 invalid request, 401 unauthenticated, 403 non-OWNER, 404 unknown product, 409 duplicate product name.

Validation: names are non-blank and at most 100 characters; descriptions are optional and at most 500 characters; unit and price are required on create/update; update status is required. Prices use `BigDecimal`, must be nonnegative with at most 10 integral digits and 2 fractional digits. Zero is allowed. Invalid precision is rejected rather than rounded.

Product names have a database uniqueness constraint. Matching semantics follow the database collation; case-insensitive uniqueness is not implemented.

`Product.price` is the current catalog price only. Implemented DailyRecord creation stores the applicable unit price snapshot and calculates the record amount from that snapshot, so later Product price changes do not change historical transactions or bills. The owner supplies the snapshot for a new chargeable record; it is not automatically copied from the current catalog price.

There is no delete endpoint. Inactive products remain visible to OWNERs. Customer product browsing is **PLANNED / NOT YET IMPLEMENTED** and is not part of this module.

## Subscription Management (IMPLEMENTED)

Subscriptions represent recurring default quantities, not actual supply. All endpoints below require OWNER authorization through the existing `/api/owner/**` rule. Controllers return `SubscriptionResponse` DTOs, never JPA entities.

1) `POST /api/owner/subscriptions`
- Request DTO: `OwnerCreateSubscriptionRequest` (`customerId`, `productId`, `defaultQuantity`, `startDate`, optional `endDate`)
- Status defaults to `ACTIVE`.
- Success: 201 Created with a Location header and `SubscriptionResponse`.
- Errors: 400 invalid request, 401 unauthenticated, 403 non-OWNER, 404 unknown Customer/Product, 409 inactive Customer/Product or overlapping active period.

2) `GET /api/owner/subscriptions`
- Returns all subscriptions or filters by optional `customerId`, `productId`, `status`, and `effectiveOn`.
- `effectiveOn` selects ACTIVE subscriptions whose inclusive date range contains that date.
- Success: 200 OK with a JSON array.
- Errors: 400 malformed filter, 401 unauthenticated, 403 non-OWNER.

3) `GET /api/owner/subscriptions/{subscriptionId}`
- Success: 200 with `SubscriptionResponse`.
- Errors: 400 malformed UUID, 401 unauthenticated, 403 non-OWNER, 404 unknown subscription.

4) `PUT /api/owner/subscriptions/{subscriptionId}`
- Request DTO: complete `OwnerUpdateSubscriptionRequest` (`defaultQuantity`, `startDate`, optional `endDate`, `status`). Customer and Product cannot be changed.
- Future-starting subscriptions may change quantity and dates. Once started, quantity and start date are immutable; end date may only be shortened to today or later. A cancelled subscription cannot be reactivated.
- Success: 200 with `SubscriptionResponse`.
- Errors: 400 invalid input, 401 unauthenticated, 403 non-OWNER, 404 unknown subscription, 409 overlap, ineligible Customer/Product, or forbidden historical edit.

Validation: quantity is `BigDecimal`, strictly positive, at most five integral and three fractional digits (`NUMERIC(8,3)`); invalid precision is rejected, never rounded. Start date is required; end date is optional and inclusive, and cannot precede start date. A null end date means ongoing. Invalid enum values, dates, and UUIDs return 400.

Only ACTIVE subscriptions participate in overlap checks. For the same Customer and Product, inclusive date periods may not overlap; PAUSED/CANCELLED rows remain stored and do not block a new ACTIVE period. An ACTIVE subscription requires an ACTIVE Customer and Product. Customer/Product references cannot be changed after creation.

`Subscription.defaultQuantity` is a recurring default only. Implemented DailyRecord entries capture actual supply and snapshot their applied unit price; neither Product price changes nor Subscription edits may recalculate historical DailyRecords or Bills.

5) `GET /api/customer/subscriptions`
- CUSTOMER-only, read-only endpoint. The Customer is resolved from the authenticated User ID; no customer identifier is accepted for selection. Supplying `customerId` is rejected with 400.
- Returns a JSON array of `CustomerSubscriptionResponse`: `id`, `productName`, `productUnit`, `defaultQuantity`, `status`, `startDate`, nullable `endDate`, `effectiveToday`, and `periodState`.
- Results are scoped to that Customer and ordered by descending start date. Product name/unit are fetched with the associated Product and no JPA entities or Customer/Product IDs are serialized.
- `periodState` describes dates only: UPCOMING when `startDate` is after the India business date, ENDED when the inclusive `endDate` is before it, otherwise IN_PERIOD. Null `endDate` means no end bound.
- `effectiveToday` is true only when status is ACTIVE and the inclusive dates contain the India business date. PAUSED/CANCELLED subscriptions can be within their dates but are not effective; ACTIVE subscriptions outside their dates are not effective either.
- Success: 200 OK, including an empty array when no subscriptions exist. Errors: 400 if `customerId` is supplied or malformed, 401 unauthenticated, 403 non-CUSTOMER.
- Default quantity is a plan, not evidence of actual collection; use customer DailyRecords for recorded supply.

BillingPeriod inspection/closing and Bill generation are implemented below. Application-level overlap checking is implemented. **PLANNED / NOT YET IMPLEMENTED:** Payment and database-level protection against concurrent overlapping writes for PostgreSQL.

6) `GET /api/customer/bills`
- CUSTOMER-only, read-only endpoint. The Customer is resolved from the authenticated User ID; supplied `customerId` is rejected with 400 and never selects records.
- Returns only bills with `status = ISSUED` and non-null `issuedAt`. DRAFT bills are excluded. The existing Bill database check requires issue metadata for ISSUED rows and no issue metadata for DRAFT rows.
- Request parameters: `page` (default 0) and `size` (default 20, range 1-100). Results use stable `createdAt DESC, id ASC` ordering and an explicit `CustomerBillPageResponse` page envelope.
- `CustomerBillResponse` contains bill ID, billing period (`YYYY-MM`), inclusive period dates, total amount, ISSUED status, issue timestamp, and line summaries (record date, slot, description snapshot, quantity, unit-price snapshot, and amount). It excludes customer PII, issuer identity, bill-line IDs, and DailyRecord IDs.
- Currency is not stored on Bill; the API does not claim a currency and the UI renders the numeric amounts without a currency symbol.
- Page metadata includes `content`, `number`, `size`, `totalElements`, and `totalPages`. Empty results return an empty page. Errors: 400 invalid page/size or supplied customerId, 401 unauthenticated, 403 non-CUSTOMER.
- Each bill page is loaded first, then its lines are batch-loaded for only those visible bill IDs; this avoids collection-fetch pagination and per-bill line queries.
- ISSUED means the owner explicitly issued/finalized the bill. It does not mean paid. Payment status is unavailable because no payment ledger or allocation model exists.

## DailyRecord Management (IMPLEMENTED)

DailyRecord records the actual product outcome for one customer, product, calendar date, and collection slot. It is distinct from Subscription, which stores the recurring default plan. Customers collect at the farm; these APIs do not model delivery. Creating or updating a DailyRecord does not mutate Subscription defaults/status or Product catalog price.

All endpoints are OWNER-only under `/api/owner/**`. Successful responses use `DailyRecordResponse`, never JPA entities.

1) `POST /api/owner/daily-records`
- Request DTO: `OwnerCreateDailyRecordRequest` (`customerId`, `productId`, optional `subscriptionId`, `recordDate`, `slot`, `quantity`, `unitPriceSnapshot`, `status`)
- Chargeable statuses COLLECTED/EXTRA require positive quantity and owner-supplied unit price snapshot. SKIPPED/PAUSED require zero quantity and null price. Amount is server-calculated.
- Supports today and past dates; past records require their historical price snapshot.
- Success: 201 Created with Location header.
- Errors: 400 invalid input; 401 unauthenticated; 403 non-OWNER; 404 unknown Customer/Product/Subscription; 409 duplicate, ineligible entity, or invalid Subscription association.

2) `GET /api/owner/daily-records`
- Optional filters: `customerId`, `productId`, `recordDate` or inclusive `fromDate`/`toDate`, `slot`, `status`.
- Supports `page` (default 0) and `size` (default 50, maximum 100); default order is recordDate DESC, slot ASC, id ASC.
- `recordDate` cannot be combined with range filters; fromDate cannot be after toDate.
- Success: 200 with a paged `DailyRecordResponse` result.

3) `GET /api/owner/daily-records/{dailyRecordId}`
- Success: 200 response DTO; malformed UUID 400; unknown record 404.

4) `PUT /api/owner/daily-records/{dailyRecordId}`
- Request DTO accepts only `quantity`, `status`, and optional `unitPriceSnapshot`.
- Only records dated today may be corrected. Past records return 409. Identity references, date, slot, and existing price snapshots cannot be changed.
- A no-supply record may become chargeable only with a new snapshot; a chargeable record changing to no-supply clears price and amount.
- Success: 200; invalid correction 400/409; missing record 404.

DailyRecord statuses are exactly COLLECTED, SKIPPED, PAUSED, and EXTRA. Quantity uses `BigDecimal NUMERIC(8,3)`; snapshots use `NUMERIC(12,2)`. Chargeable amount is calculated from quantity × snapshot and rounded once to 2 decimals using HALF_UP, then stored as `NUMERIC(18,2)`. Product.price is not consulted after creation.

One row is allowed per `(customer_id, product_id, record_date, slot)` across all statuses. A supplied Subscription must match Customer/Product and include the record date, but need not currently be ACTIVE. No Subscription is required or inferred. Positive supply requires currently ACTIVE Customer and Product.

### Owner web workflow (IMPLEMENTED)

The Owner "Daily records" workspace calls these endpoints unchanged; no endpoint, DTO, or status code was added or modified for it. The frontend submits only intent — customer, product, date, slot, status, quantity, and an explicit unit-price snapshot — and never sends `amount`, `subscriptionId`, or timestamps.

- Reference data comes from `GET /api/owner/customers` and `GET /api/owner/products`. Customer name/phone/status and product name/unit/status are display labels resolved in the browser; `DailyRecordResponse` returns only `customerId`/`productId`.
- The unit-price snapshot is prefilled from the current `Product.price` for operator convenience, but the owner-submitted value is what is stored. The client never recalculates or infers the snapshot from Subscription defaults.
- The review list uses `recordDate` plus the optional `customerId`, `productId`, `slot`, and `status` filters with `page`/`size`; it does not use `fromDate`/`toDate` yet even though the endpoint supports them.
- Correction reuses `PUT` and is exposed only for records dated today, matching the existing rule that past records return 409.
- Duplicate-slot, closed-period, eligibility, and future-date conflicts surface as 409 and are shown to the owner as-is; no client-side business rule replaces them.
- **PLANNED / NOT YET IMPLEMENTED:** a multi-day range control in the owner review list, and any owner-facing DailyRecord analytics or summaries.

### Owner customer and product setup (IMPLEMENTED)

The Owner "Customers" and "Products" workspaces call the existing customer and product endpoints unchanged. No endpoint, DTO, status code, or database change was introduced for this workflow.

Customer requests:
- `POST /api/owner/customers` provisions the customer login. The request carries `phone`, `password`, `name`, and optionally `address`, `billingType`, `status`, and `joinDate`. The server always creates the linked `User` with role CUSTOMER and a BCrypt hash; the password is never echoed in any response.
- `PUT /api/owner/customers/{customerId}` is a **full replacement**. `name` is required and `billingType`, `status`, and `joinDate` are `@NotNull`, so the client must always send all of them. The request DTO has **no** phone field: a phone number cannot be changed after creation.
- `GET /api/owner/customers` returns every customer unpaginated and accepts **no** query parameters. The workspace's name/phone search and status filter therefore run in the browser over the full list.
- A duplicate phone returns 409, an unknown customer 404, and a missing or malformed field 400.

Product requests:
- `POST /api/owner/products` accepts `name`, `description`, `unit`, and `price` only. **There is no status field**: `ProductService` always provisions a new product as `ACTIVE`, so the client omits status entirely on create.
- `PUT /api/owner/products/{productId}` is a full replacement and **requires** `status`, which is how activation and deactivation are performed. No delete endpoint exists; `INACTIVE` is the only removal mechanism the domain supports.
- A duplicate product name returns 409 via `uk_products_name`, and price is constrained to a non-negative `NUMERIC(12,2)`.

Historical immutability when editing a product:
- Changing `price` does not alter historical `DailyRecord` amounts or issued bills. Each record stores its own `unitPriceSnapshot` and `amount`, and `BillLine` persists `description`, `unit_price`, and `amount` as `updatable = false`. The billing package never reads `ProductRepository`.
- Changing `name` or `unit` **does** change how already-collected records are labelled on the customer-facing endpoints, because `CustomerDailyRecordController` and `CustomerSubscriptionController` resolve `getProduct().getName()` and `getUnit()` live rather than from a snapshot. The owner workspace warns about this and requires explicit confirmation before saving such a change.

**PLANNED / NOT YET IMPLEMENTED:** password reset or password change for any account, SMS phone verification, owner-side customer search/pagination on the server, customer deletion, product deletion, and any owner-facing subscription management.

## Billing Period Management (IMPLEMENTED)

Billing periods are calendar months identified by `YYYY-MM`, with inclusive canonical first/last dates. Period inspection/closing and per-customer Bill generation/issuing are implemented.

1) `GET /api/owner/billing-periods/{period}`
- Returns a `BillingPeriodResponse` with dates, OPEN/CLOSED state, closure metadata, and an informational chargeable-record count/amount.
- A missing period row is represented as OPEN without creating a row. Existing DailyRecords are included in the summary.
- The summary includes only COLLECTED/EXTRA DailyRecords and does not prove that every physical collection was recorded.

2) `POST /api/owner/billing-periods/{period}/close`
- Closes the calendar month after owner reconciliation; empty months may be closed.
- Only completed months may be closed; current and future month inspection remains available.
- Closing is idempotent; repeated requests return the persisted CLOSED period and original closure metadata.
- The response includes the chargeable-record summary calculated while the period is locked.
- Errors: 400 malformed/impossible `YYYY-MM`; 401 unauthenticated; 403 non-OWNER; 409 current/future month close or DailyRecord write attempted for a CLOSED period.

3) `POST /api/owner/billing-periods/{period}/bills/{customerId}`
- Generates a DRAFT bill for one customer from actual COLLECTED/EXTRA DailyRecords in the closed period. PREPAID customers are rejected; SKIPPED/PAUSED records are excluded. Subscription defaults are not billed.
- Customer details, product/unit description, DailyRecord date/slot, quantity, unit price, and amount are copied to bill snapshots. The bill total is the sum of stored DailyRecord amounts; current Product.price is not read for pricing.
- Customer and product/unit labels are copied as they exist when the Bill is generated; DailyRecord itself does not snapshot those labels.
- Empty or zero-total bills are rejected with 409. Repeating generation returns the existing bill. Generation returns 200 to support this idempotent behavior.

4) `GET /api/owner/bills/{billId}` and `GET /api/owner/bills?customerId={uuid}&period=YYYY-MM&page=0&size=50`
- Retrieve a bill with immutable lines or filter the paginated owner list. Page size is 1-100.

5) `POST /api/owner/bills/{billId}/issue`
- Transitions a DRAFT to ISSUED and records timestamp and owner ID. Repeated issue returns the existing issued snapshot. No update or reopen endpoint exists.

Errors: 400 malformed/impossible period, invalid UUID, or invalid pagination; 401 unauthenticated; 403 non-OWNER; 404 unknown bill/customer; 409 period is not CLOSED, customer is not MONTHLY, or bill has no nonzero chargeable records.

DailyRecord writes initialize an OPEN period row if needed and acquire the same database row lock held during closing. No DailyRecord may be created or updated after closure. Same-day corrections and backdated inserts remain permitted while the relevant period is OPEN. Read-only inspection does not create period rows. Initializing a period row uses a separate transaction and a named unique key to safely resolve concurrent first-time creation.

No customer-eligibility snapshot is currently captured when a period closes. Accordingly, generation checks the customer's current `MONTHLY` billing type; customer status is not an additional exclusion when qualifying persisted chargeable records exist. Currency is not stored; INR is an unconfirmed assumption only. Payment, credit/debit allocation, late charges, and BalanceAdjustment remain **PLANNED / NOT YET IMPLEMENTED**. H2 tests do not prove PostgreSQL concurrency safety.

Error handling
- Validation errors return 400 with a field error message (handled by ControllerAdvice in CustomerExceptionHandler)
- Domain errors: PhoneAlreadyRegisteredException -> 409; CustomerNotFoundException -> 404
- Authentication errors: handled by SecurityConfig / Spring Security (401 or 403 as appropriate)

Security considerations
- Never return passwordHash in responses
- Token must be supplied as Authorization: Bearer <token>

Notes
- DTOs are used to decouple API from JPA entities (see ADR-006)
- Controller paths align with role-based requirements defined in SecurityConfig

## Payment and Balance API Design (PROPOSED; NOT IMPLEMENTED)

No payment, allocation, adjustment, account, or balance endpoint currently exists. The following contracts are design proposals only; details and unresolved policy are in [Payment and Balance Domain Design](./payment-domain-design.md) and [ADR-010](./decisions/ADR-010-customer-financial-ledger.md).

Recommended owner operations, all under `/api/owner/**` and OWNER-only:

- `POST /api/owner/customers/{customerId}/payments` records a positive, confirmed receipt with required `Idempotency-Key`; optional explicit allocations may target only the same customer's ISSUED bills. Unallocated remainder remains account credit.
- `POST /api/owner/payments/{paymentId}/allocations` explicitly allocates previously unallocated funds. Allocation releases/reallocation require an approved correction policy and append-only compensating events.
- `POST /api/owner/customers/{customerId}/balance-adjustments` records a reasoned DEBIT or CREDIT, with optional same-customer issued-bill scope. An adjustment is not money received.
- `GET /api/owner/customers/{customerId}/account` and `/transactions` provide a derived summary and bounded, stable-sorted statement.

Recommended CUSTOMER-only, read-only operations:

- `GET /api/customer/account`
- `GET /api/customer/transactions?page=0&size=20`
- `GET /api/customer/bills/{billId}` for an ownership-scoped balance/allocation detail, if a separate detail route is justified. The existing customer bill list does not expose payment status or balance.

Customer endpoints must resolve Customer from authenticated User ID and constrain each query by that Customer. Do not accept customer ID input as authorization. Reject unknown, non-issued, cross-customer, over-allocated, or currency-mismatched bill operations; use bounded pagination and stable sort keys. A persisted idempotency key protects retries; a reused key with a different payload conflicts. Exact request/response fields, validation, error mappings, currency prerequisites, actor attribution, and transaction requirements remain proposed, not implemented.

Never infer `paid`, `unpaid`, or outstanding balance from ISSUED status or bill total alone. Currency and existing-bill interpretation must be explicitly confirmed before any financial API is implemented.

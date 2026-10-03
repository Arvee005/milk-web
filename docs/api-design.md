# API Design

This document records the API endpoints implemented in the project and their request/response shapes, validation, and security.

Existing endpoints (verified in code)

1) POST /api/auth/login
- Controller: AuthController.login
- Request: LoginRequest { phone: 10-digit string, password }
- Response: AuthService.LoginResponse { token, userId, phone, role }
- Validation: phone 10-digit pattern, password not blank
- Security: public (permitAll)
- Errors: 401 Unauthorized for bad credentials

2) GET /api/auth/me
- Controller: AuthController.me
- Returns: current authenticated user info (userId, phone, role)
- Security: authenticated

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

`Product.price` is the current catalog price only. **PLANNED / NOT YET IMPLEMENTED:** future `DailyRecord` entries must snapshot the applicable unit price so later Product price changes do not change historical transactions or bills.

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

**PLANNED / NOT YET IMPLEMENTED:** customer-facing subscription APIs, Bill generation, and Payment. BillingPeriod inspection/closing is implemented below. Application-level overlap checking is implemented. **PLANNED / NOT YET IMPLEMENTED:** database-level protection against concurrent overlapping writes for PostgreSQL.

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

## Billing Period Management (IMPLEMENTED)

Billing periods are calendar months identified by `YYYY-MM`, with inclusive canonical first/last dates. This module implements period inspection and explicit closing only; it does not generate or issue Bills.

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

DailyRecord writes initialize an OPEN period row if needed and acquire the same database row lock held during closing. No DailyRecord may be created or updated after closure. Same-day corrections and backdated inserts remain permitted while the relevant period is OPEN. Read-only inspection does not create period rows. Initializing a period row uses a separate transaction and a named unique key to safely resolve concurrent first-time creation.

**PLANNED / NOT YET IMPLEMENTED:** Bill generation, immutable Bill line snapshots, Payment, and BalanceAdjustment. Bills will be generated only for closed periods and from actual chargeable DailyRecords; Subscriptions do not generate charges. H2 concurrency tests do not prove PostgreSQL concurrency safety.

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

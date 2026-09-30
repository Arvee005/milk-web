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

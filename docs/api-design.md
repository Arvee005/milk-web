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

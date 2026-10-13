# Security

This document explains the authentication and authorization mechanisms used by Milk Web.

Password hashing
- BCrypt (via Spring's BCryptPasswordEncoder) is used.
- Passwords are not stored in plaintext; User.passwordHash stores the hashed value.
- Rationale: BCrypt is slow-by-design to mitigate brute-force attacks (see ADR-002).

JWT tokens
- JwtService generates JWT tokens signed with an HMAC secret key (configured via milkweb.jwt.secret).
- Tokens include subject (userId), phone, and role claims, and an expiration timestamp.
- JwtAuthenticationFilter validates tokens on each request and sets SecurityContext with MilkWebUserDetails.

SecurityContext and authentication
- MilkWebUserDetails wraps User and exposes username (phone), password (hash) and authorities (ROLE_<role>). It provides userId for controllers.
- AuthController provides /api/auth/login which returns a token on successful authentication.

Authorization
- SecurityConfig enforces role-based restrictions:
  - /api/owner/** requires ROLE_OWNER
  - /api/customer/** requires ROLE_CUSTOMER
  - POST /api/auth/login and /actuator/health are public
- Controllers use @PreAuthorize where needed and rely on URL-based restrictions.
- The browser checks `/api/auth/me` before displaying either protected workspace and selects OWNER/CUSTOMER UI only from the verified response role. This is a usability boundary, not an authorization control; every protected API still enforces its role on the backend.

Frontend session handling (IMPLEMENTED)
- Login sends the entered 10-digit phone and password to `POST /api/auth/login`; the password is not persisted.
- The returned bearer token is stored in `sessionStorage` for the current tab, then `/api/auth/me` validates the token and persisted user role before rendering the owner or customer workspace.
- A 401 clears the stored session and returns to sign-in with an expired-session message. A 403 displays a permission message without treating the still-authenticated identity as expired.
- OWNER and CUSTOMER sessions use the same token storage lifecycle. Workspace selection uses `/api/auth/me`, not the role from the login response; a customer token cannot render the owner workspace, and an owner token cannot render the customer workspace.
- `sessionStorage` was retained instead of `localStorage` so closing the tab ends browser persistence. It is still readable by same-origin JavaScript and therefore does not eliminate XSS risk. HttpOnly cookies would reduce script access but require a different backend cookie/CSRF design and are not introduced here.
- Refresh tokens and revocation are not implemented. The customer UI exposes the authenticated profile, today's and historical DailyRecords, read-only subscriptions, and issued bills. Payment history and balances are not available.

Product Management (IMPLEMENTED)
- OWNER product APIs are under `/api/owner/products` and use the existing `/api/owner/**` ROLE_OWNER restriction.
- Product controllers return ProductResponse DTOs, not JPA entities.
- Product request DTOs do not accept IDs or timestamps; create defaults status to ACTIVE, while update can change status.
- Product data contains no authentication credentials. Customer product browsing is PLANNED / NOT YET IMPLEMENTED and unauthenticated catalog access is not enabled.

Subscription Management (IMPLEMENTED)
- OWNER subscription endpoints use the existing `/api/owner/**` ROLE_OWNER rule; SecurityConfig was not changed.
- Unauthenticated requests receive 401; CUSTOMER requests receive 403.
- Request/response DTOs expose related Customer/Product UUIDs only and do not serialize JPA relationships or authentication data.
- `GET /api/customer/subscriptions` is CUSTOMER-only and read-only. It derives the customer from the authenticated User ID and every repository query is constrained to that Customer.
- The customer API rejects a supplied `customerId`; it cannot be used to select or broaden access to another account. The DTO includes only subscription and product display fields, status, inclusive dates, and derived date/effectiveness flags.
- `effectiveToday` requires both ACTIVE status and an inclusive date range containing the configured India business date. Status and dates are returned separately so PAUSED/CANCELLED in-range records and ACTIVE out-of-range records are not incorrectly shown as currently effective.
- OWNER and unauthenticated callers remain rejected by the existing `/api/customer/**` role rule. Customer-facing subscription mutation APIs do not exist.

Bill access (IMPLEMENTED)
- `GET /api/customer/bills` requires CUSTOMER via the existing `/api/customer/**` security rule. The service resolves the Customer through the authenticated User ID and constrains both page and line-item queries to that Customer.
- The endpoint rejects any supplied `customerId`; client input cannot select another account's data. OWNER requests receive 403 and anonymous requests receive 401.
- Only `ISSUED` bills with `issuedAt` are returned. DRAFT bills are excluded regardless of whether their billing period is closed. This uses the existing bill lifecycle rather than introducing a new visibility state.
- Customer DTOs omit customer PII snapshots, owner/issuer identifiers, JPA relations, bill-line IDs, and source DailyRecord IDs. Only the bill summary and immutable line values needed for customer review are exposed.
- `ISSUED` is not a paid/unpaid signal. No payment or balance data is returned or inferred.

Payment and balance (DESIGN ONLY; NOT IMPLEMENTED)
- No endpoint currently records payments, allocations, adjustments, or returns a customer balance. The OWNER/CUSTOMER rules below are proposals, not active routes.
- Proposed money-changing operations (receipt, allocation/release, adjustment) require OWNER and derive the actor from the authenticated principal. Client-supplied `recordedBy`, customer identity, or balance is never trusted.
- Proposed customer reads derive Customer through authenticated User ID and constrain every account, bill, allocation, and statement query to that Customer. A supplied customer ID must be rejected; an out-of-scope bill ID must not disclose another account.
- Proposed receipts, allocations, and adjustments are append-only; corrections require attributable compensating events and reasons. Required idempotency keys protect retried writes. Database transactions and locking must prevent concurrent over-allocation.
- `PREPAID` does not currently represent a payment or credit workflow. Do not infer payment, balance, or service eligibility from that enum.
- Currency is not stored on current bills. Confirm currency and historical interpretation before enabling financial writes; do not backfill a guessed INR code.
- See [Payment and Balance Domain Design](./payment-domain-design.md) and [ADR-010](./decisions/ADR-010-customer-financial-ledger.md) for proposed roles, data isolation, audit, concurrency, and unresolved policy. No payment-security implementation or tests are claimed.

DailyRecord Management (IMPLEMENTED)
- OWNER DailyRecord APIs use the existing `/api/owner/**` ROLE_OWNER rule; SecurityConfig was not modified.
- Unauthenticated requests receive 401; CUSTOMER requests receive 403.
- DTOs expose related resource IDs and recorded values only; they do not expose nested entities or authentication information.
- `GET /api/customer/daily-records` is CUSTOMER-only and read-only. It derives the Customer from the authenticated User ID and constrains every query by that Customer ID; it rejects `customerId` query parameters rather than using them as filters.
- The customer response DTO contains only record ID/date/slot, product name/unit, quantities, historical price snapshot, amount, and status. Pagination is capped at 100; date ranges require both endpoints and are capped at 366 calendar dates.
- Owner daily-record create/list/detail/update endpoints remain unchanged and OWNER-only.

Owner DailyRecord workspace (IMPLEMENTED)
- The Owner "Daily records" section calls only existing `/api/owner/**` endpoints. `SecurityConfig` was not changed and no new authorization rule was introduced; the frontend section is a usability boundary, not an access control.
- A 401 from any owner request clears the stored session and returns to sign-in through the existing session-expired callback. A 403 is displayed as a permission error and does not expire the authenticated identity.
- The workspace requests `/api/owner/customers` and `/api/owner/products` to render labels. It receives only existing `CustomerResponse`/`ProductResponse` DTO fields; no additional customer or product data is exposed to the browser.
- The unit-price snapshot is prefilled from the current catalog price as a convenience, but the value stored is the owner-submitted one. The client never computes an amount and never treats the prefilled value as authoritative.
- No token is written to storage by this workspace; it uses the same tab-scoped `sessionStorage` lifecycle as the owner billing workspace and shares the single sign-out control.

Owner customer and product setup (IMPLEMENTED)
- The Owner "Customers" and "Products" sections call only existing `/api/owner/**` endpoints. `SecurityConfig` was not modified and no new authorization rule, filter, or role was introduced; these frontend sections are usability boundaries, not access controls.
- Customer creation is **user provisioning**. The plaintext password is sent once over the existing authenticated channel in the `POST /api/owner/customers` body, is hashed server-side with BCrypt, and is never returned: `CustomerResponse` contains no `passwordHash` or any authentication field, so no hash can reach the browser.
- The password input uses `type="password"` with `autoComplete="new-password"`, is removed entirely when editing an existing customer, and is cleared from component state after a successful create. It is never persisted to `localStorage`, `sessionStorage`, or any other browser storage.
- **No password-reset or password-change capability exists anywhere in the backend.** The owner sets a customer's initial password once and cannot change or recover it later. The UI states this next to the password field and again in the success notice, instructing the owner to share the password over a trusted channel. Treat a customer created with a weak or forgotten password as requiring a new customer record until a reset feature exists.
- A customer's phone number is the login identifier and is immutable after creation: `OwnerUpdateCustomerRequest` has no phone field, and the phone input is read-only in edit mode. The client never sends `phone` or `password` on update.
- Customer and product search and status filtering run entirely in the browser over data the owner is already authorized to fetch. No filter bypasses the `/api/owner/**` rule.
- Raw backend response bodies and error objects are never rendered. Only the message string extracted by `apiRequest` is displayed; 401 expires the session, 403 renders as a permission error without expiring it, and 409/400 render the server message while preserving entered form values.
- Neither section renders or transmits a password hash, JWT secret, or database credential. No secret was added to the frontend in this slice.

**PLANNED / NOT YET IMPLEMENTED:** password reset, password change, multi-factor or invite-based customer onboarding, and server-side customer search.

Billing and Period Management (IMPLEMENTED)
- Owner period inspection and close endpoints use the existing `/api/owner/**` ROLE_OWNER rule; SecurityConfig was not modified.
- Unauthenticated requests receive 401; CUSTOMER requests receive 403.
- Responses expose only period state, dates, aggregate count/amount, timestamps, and the closing User UUID; no User or authentication data is serialized.
- Bill generation, retrieval, and issuing use the same `/api/owner/**` ROLE_OWNER rule. DTOs expose historical customer and bill-line snapshots but no nested User data or authentication fields.
- Customer bill reads are available only through the CUSTOMER-scoped `GET /api/customer/bills` described above; the owner generation/retrieval/issue endpoints remain OWNER-only.

Secrets and configuration
- JWT secret is read from configuration (`milkweb.jwt.secret`), which is bound to the
  `MILKWEB_JWT_SECRET` environment variable. There is **no committed default**: the
  production property file leaves it empty, and `application-dev.properties` /
  `application-test.properties` each supply a dev/test-only secret that is **never**
  used in production.
- **Do NOT commit production secrets.** Generate a random value of at least 32 bytes
  (256 bits) per environment and supply it through the environment or a secrets manager.
- `JwtService` validates the secret at construction time and fails the Spring context
  refresh with a clear message if it is missing, blank, or shorter than 32 bytes — the
  HS256 minimum. This prevents an empty default from silently producing forgeable
  tokens. Tests that exercise `JwtService` directly must supply a strong secret; the
  test property file already does.
- `JwtSecretValidationTest` covers missing, blank, and too-short secrets, and confirms
  that a 32-byte secret is accepted.

Cross-origin (CORS) configuration (IMPLEMENTED)
- CORS is configured through `SecurityConfig.corsConfigurationSource`, driven by the
  `milkweb.cors.allowed-origins` property, which is bound to the
  `MILKWEB_CORS_ALLOWED_ORIGINS` environment variable.
- The list is **empty by default**, so a misconfigured deployment does not silently
  fall back to allowing every origin. Set it explicitly for the environment that
  serves the frontend, e.g. `MILKWEB_CORS_ALLOWED_ORIGINS=https://app.example.com`.
- Allowed headers: `Authorization`, `Content-Type`, `Accept`, `Origin`,
  `X-Requested-With`. Allowed methods: `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`.
- `allowCredentials` is **false**. Authentication uses a stateless Bearer token in the
  `Authorization` header, so cookies are not used and enabling credentials would add
  risk without benefit.
- `CorsConfigurationTest` verifies preflight responses for allowed origins, the 403
  response for disallowed origins, and the absence of an
  `Access-Control-Allow-Credentials` header.
- Existing authorization rules are unchanged: `/api/auth/login` is public,
  `/actuator/health` is public, `/api/owner/**` requires OWNER, `/api/customer/**`
  requires CUSTOMER, and every other request requires authentication.

Development owner seeding (IMPLEMENTED)
- `config/DevOwnerSeeder` is an `ApplicationRunner` annotated `@Profile("dev")`, so the bean does not exist at all unless the `dev` profile is active. It creates the first OWNER account from `milkweb.dev-owner.phone` and `milkweb.dev-owner.password`, which `application-dev.properties` binds to the `MILKWEB_DEV_OWNER_PHONE` and `MILKWEB_DEV_OWNER_PASSWORD` environment variables.
- The raw password is passed to `UserService.createUser(phone, rawPassword, UserRole.OWNER)`, so it is hashed by the same BCrypt encoder used by customer provisioning. The password value is never logged; a validation warning may report its length in characters or UTF-8 bytes. No default or committed credential exists.
- A runtime guard requires `dev` to be the **only** active profile. Starting with `dev` plus any other profile (for example `dev,prod`) logs a warning and creates nothing, so this cannot become a backdoor when a real profile is added later.
- Missing or invalid configuration skips seeding and logs an actionable message. The phone must match `\d{10}`, and the password must be at least 8 characters (matching `OwnerCreateCustomerRequest`) and at most 72 UTF-8 bytes, which is BCrypt's limit. Length is measured in bytes because a character count understates a multi-byte password. There is no fallback credential.
- Seeding is idempotent. An existing OWNER is left untouched, including its password; an existing non-owner account is reported and never silently promoted to OWNER, because that would be an unintended privilege escalation.
- This is a local-development convenience only. PLANNED / NOT YET IMPLEMENTED: owner password change or reset, and any authentication rate limiting.

Production owner seeding (IMPLEMENTED)
- `config/ProdOwnerSeeder` is an `ApplicationRunner` annotated `@Profile("prod")`, so the bean does not exist at all unless the `prod` profile is active. It creates the first OWNER account from `milkweb.prod-owner.phone` and `milkweb.prod-owner.password`, which `application-prod.properties` binds to the `MILKWEB_PROD_OWNER_PHONE` and `MILKWEB_PROD_OWNER_PASSWORD` environment variables.
- The feature is **disabled by default**: both environment variables must be set for the seeder to create anything, and there is no committed or fallback credential. When configuration is missing or invalid, the seeder logs an actionable message and creates nothing.
- The raw password is passed to `UserService.createUserResilient(phone, rawPassword, UserRole.OWNER)`, so it is hashed by the same BCrypt encoder used by customer provisioning. The password value is never logged; a validation warning may report its length in characters or UTF-8 bytes.
- A runtime guard requires `prod` to be the **only** active profile. Starting with `prod` plus any other profile (for example `prod,dev`) logs a warning and creates nothing, so this cannot become a backdoor when another profile is added later.
- Seeding only happens when the users table is empty (`userService.countUsers() == 0`), so it can never overwrite or duplicate an existing account, and an existing non-owner account is never silently promoted to OWNER.
- Configuration validation mirrors the dev seeder and the customer-provisioning contract: the phone must match `\d{10}`, and the password must be at least 8 characters and at most 72 UTF-8 bytes (BCrypt's hard limit). Length is measured in bytes because a character count understates a multi-byte password.
- Seeding is idempotent: a repeated invocation with the same configuration leaves the existing OWNER's password and role unchanged.
- This is a production bootstrap only, gated behind the `prod` profile and opt-in environment variables. PLANNED / NOT YET IMPLEMENTED: a CLI or out-of-band bootstrap flow, owner password change or reset, and any authentication rate limiting.

Token expiration and rotation
- Tokens have an expiration (default 24 hours). Refresh strategies are not currently implemented; consider refresh tokens or short-lived access tokens for mobile clients.

Protection measures
- Never expose passwordHash in API responses (DTOs prevent this).
- Validate input (phone pattern, required fields) to reduce injection/invalid data risks.
- The OWNER customer profile update endpoint does not accept or modify password, passwordHash, role, or the phone/login identifier. This is a milestone-level endpoint decision; a dedicated account-identifier or credential flow may be designed later.

Future security recommendations (PLANNED)
- Use HTTPS in production and enforce secure cookie or Authorization header usage on mobile.
- Add rate-limiting on authentication endpoints to mitigate brute force.
- Implement secure secret storage (e.g., environment variables, vault) for JWT secret.
- Consider rotating keys and supporting JWKS if moving to public-key tokens.

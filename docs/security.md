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

Billing and Period Management (IMPLEMENTED)
- Owner period inspection and close endpoints use the existing `/api/owner/**` ROLE_OWNER rule; SecurityConfig was not modified.
- Unauthenticated requests receive 401; CUSTOMER requests receive 403.
- Responses expose only period state, dates, aggregate count/amount, timestamps, and the closing User UUID; no User or authentication data is serialized.
- Bill generation, retrieval, and issuing use the same `/api/owner/**` ROLE_OWNER rule. DTOs expose historical customer and bill-line snapshots but no nested User data or authentication fields.
- Customer bill reads are available only through the CUSTOMER-scoped `GET /api/customer/bills` described above; the owner generation/retrieval/issue endpoints remain OWNER-only.

Secrets and configuration
- JWT secret is read from configuration (milkweb.jwt.secret). Defaults should not be committed. For dev, application-dev.properties contains a dev-only secret.
- Do NOT commit production secrets.

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

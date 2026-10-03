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

Product Management (IMPLEMENTED)
- OWNER product APIs are under `/api/owner/products` and use the existing `/api/owner/**` ROLE_OWNER restriction.
- Product controllers return ProductResponse DTOs, not JPA entities.
- Product request DTOs do not accept IDs or timestamps; create defaults status to ACTIVE, while update can change status.
- Product data contains no authentication credentials. Customer product browsing is PLANNED / NOT YET IMPLEMENTED and unauthenticated catalog access is not enabled.

Subscription Management (IMPLEMENTED)
- OWNER subscription endpoints use the existing `/api/owner/**` ROLE_OWNER rule; SecurityConfig was not changed.
- Unauthenticated requests receive 401; CUSTOMER requests receive 403.
- Request/response DTOs expose related Customer/Product UUIDs only and do not serialize JPA relationships or authentication data.
- Customer-facing subscription APIs are PLANNED / NOT YET IMPLEMENTED.

DailyRecord Management (IMPLEMENTED)
- OWNER DailyRecord APIs use the existing `/api/owner/**` ROLE_OWNER rule; SecurityConfig was not modified.
- Unauthenticated requests receive 401; CUSTOMER requests receive 403.
- DTOs expose related resource IDs and recorded values only; they do not expose nested entities or authentication information.
- Customer-facing DailyRecord APIs are PLANNED / NOT YET IMPLEMENTED.

Billing Period Management (IMPLEMENTED)
- Owner period inspection and close endpoints use the existing `/api/owner/**` ROLE_OWNER rule; SecurityConfig was not modified.
- Unauthenticated requests receive 401; CUSTOMER requests receive 403.
- Responses expose only period state, dates, aggregate count/amount, timestamps, and the closing User UUID; no User or authentication data is serialized.
- Bill generation and customer-facing bill APIs are PLANNED / NOT YET IMPLEMENTED.

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

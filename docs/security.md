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

Secrets and configuration
- JWT secret is read from configuration (milkweb.jwt.secret). Defaults should not be committed. For dev, application-dev.properties contains a dev-only secret.
- Do NOT commit production secrets.

Token expiration and rotation
- Tokens have an expiration (default 24 hours). Refresh strategies are not currently implemented; consider refresh tokens or short-lived access tokens for mobile clients.

Protection measures
- Never expose passwordHash in API responses (DTOs prevent this).
- Validate input (phone pattern, required fields) to reduce injection/invalid data risks.

Future security recommendations (PLANNED)
- Use HTTPS in production and enforce secure cookie or Authorization header usage on mobile.
- Add rate-limiting on authentication endpoints to mitigate brute force.
- Implement secure secret storage (e.g., environment variables, vault) for JWT secret.
- Consider rotating keys and supporting JWKS if moving to public-key tokens.

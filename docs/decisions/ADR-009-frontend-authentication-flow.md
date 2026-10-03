# ADR-009: Frontend Authentication Flow

## Decision
Use the existing JWT API for browser authentication. Keep the token in tab-scoped `sessionStorage`, validate restored tokens through `/api/auth/me`, and render the owner workspace only after the returned role is confirmed as `OWNER`.

## Status
Accepted (implemented)

## Context
The backend already provides phone/password login, signed JWTs, current-user lookup, and server-enforced OWNER/CUSTOMER access. The owner billing page had a login form but mixed auth state and billing operations, and it could render owner content while a stored token was still being checked.

## Decision Details
- Send phone/password only to `POST /api/auth/login`; do not persist the password.
- Validate the response shape and role and store only OWNER tokens in `sessionStorage`.
- Encapsulate reading, writing, and clearing the owner token behind small auth API helpers so session behavior can be tested without a browser.
- On a restored session, call `GET /api/auth/me` before showing billing content.
- Treat 401 as expired/invalid authentication: clear the token and return to sign-in. Treat 403 as insufficient permission: show a clear message without declaring the identity expired.
- Centralize the API response, bearer-header, and status handling in a small TypeScript auth API module. Keep authorization enforcement in Spring Security.
- Use Node's built-in test runner with injected `fetch` for focused frontend API-client tests; do not add a test framework dependency.

## Why We Chose It
- `sessionStorage` preserves the already implemented browser-tab session behavior and avoids retaining the JWT across browser restarts.
- `/api/auth/me` validates the current persisted account and role, rather than trusting claims stored by the browser.
- A protected render state prevents owner UI from flashing before authorization is confirmed.
- A shared API helper keeps the frontend contract aligned with the existing Spring API and remains independent of React for future clients.

## Alternatives Considered
- `localStorage`: survives browser restarts, but retains the bearer token longer; rejected for this owner workflow.
- HttpOnly cookie: reduces JavaScript token access, but needs backend cookie and CSRF changes; outside this focused client milestone.
- React Router or a generalized route framework: no other frontend pages currently require routing; a protected owner workspace gate is sufficient for the existing single-page application.
- Add Vitest/Testing Library: provides richer component testing but adds dependencies; built-in `node:test` is sufficient for the API client contract tests currently added.

## Advantages
- No backend authentication redesign or additional production dependency.
- Explicit session validation and clear 401/403 behavior.
- API client contract tests run without network access or credentials.

## Disadvantages / Trade-offs
- `sessionStorage` remains accessible to same-origin scripts and does not eliminate XSS exposure.
- Session validation needs a network request on app startup.
- Customer sign-in and customer pages are not implemented in this owner portal.
- Frontend tests cover API client behavior, not a live browser-to-production-backend workflow.

## Security Implications
- Frontend role checks are for UX only; Spring Security continues to protect owner and customer endpoints.
- Use HTTPS in production; never log the password or bearer token.
- Token revocation and refresh are not currently implemented.

## Testing
Authentication API tests cover login request/response parsing, input validation, invalid credentials, `/auth/me` bearer usage, token storage lifecycle, and 401/403 distinction. An embedded random-port backend test covers actual HTTP owner login followed by `/api/auth/me` against isolated H2 data. A subsequent headless Chrome run verified the browser flow against local Vite and Spring Boot services using a separate in-memory H2 database and synthetic accounts, including refresh, sign-out, expired-token, network-error, and role-denial behavior. Frontend build and lint were also run.

## Future Implications
Revisit token storage and renewal before adding customer-facing authentication or a production deployment with broader threat-model requirements.

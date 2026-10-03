# ADR-009: Frontend Authentication Flow

## Decision
Use the existing JWT API for browser authentication. Keep the token in tab-scoped `sessionStorage`, validate new and restored sessions through `/api/auth/me`, and select the OWNER or CUSTOMER workspace only from the role returned by that authenticated endpoint.

## Status
Accepted (implemented)

## Context
The backend already provides phone/password login, signed JWTs, current-user lookup, and server-enforced OWNER/CUSTOMER access. The initial frontend completed owner login but rejected customer credentials and had no customer workspace. Both roles need one shared login and session restoration flow without exposing owner billing to customer accounts.

## Decision Details
- Send phone/password only to `POST /api/auth/login`; do not persist the password.
- Validate the login response shape and store the token in the existing tab-scoped storage key for backward-compatible session restoration. Do not use the role in the login response to select or authorize a workspace.
- Encapsulate reading, writing, and clearing the session token behind small auth API helpers so session behavior can be tested without a browser.
- For both new and restored sessions, call `GET /api/auth/me` before showing protected content. Select the workspace from that response's role.
- Route CUSTOMER sessions to the customer workspace. Its profile, collection records, subscriptions, and issued bills use CUSTOMER-only APIs; payment and balance views remain unavailable until the financial domain is implemented.
- Treat 401 as expired/invalid authentication: clear the token and return to sign-in. Treat 403 as insufficient permission: show a clear message without declaring the identity expired.
- Centralize the API response, bearer-header, and status handling in a small TypeScript auth API module. Keep authorization enforcement in Spring Security.
- Use Node's built-in test runner with injected `fetch` for focused frontend API-client tests; do not add a test framework dependency.

## Why We Chose It
- `sessionStorage` preserves the already implemented browser-tab session behavior and avoids retaining the JWT across browser restarts.
- `/api/auth/me` validates the current persisted account and role, rather than trusting claims stored by the browser.
- A protected render state prevents either role's workspace from rendering before authentication and role validation. Owner data loading is gated to OWNER sessions; customer data requests do not accept customer IDs.
- A shared API helper keeps the frontend contract aligned with the existing Spring API and remains independent of React for future clients.

## Alternatives Considered
- `localStorage`: survives browser restarts, but retains the bearer token longer; rejected for this workflow.
- HttpOnly cookie: reduces JavaScript token access, but needs backend cookie and CSRF changes; outside this focused client milestone.
- React Router or a generalized route framework: no path-based routing is yet required; a role-selected workspace and in-shell customer navigation are sufficient for this phase.
- Add Vitest/Testing Library: provides richer component testing but adds dependencies; built-in `node:test` is sufficient for the API client contract tests currently added.

## Advantages
- No backend authentication redesign or additional production dependency.
- Explicit session validation and clear 401/403 behavior.
- API client contract tests run without network access or credentials.

## Disadvantages / Trade-offs
- `sessionStorage` remains accessible to same-origin scripts and does not eliminate XSS exposure.
- Session validation needs a network request on app startup and before any role-specific content is shown.
- The customer workspace now includes profile, records, subscriptions, and issued bills. Payment and balance APIs/views still require a separately approved and implemented financial domain.
- Frontend tests cover API client behavior, not a live browser-to-production-backend workflow.

## Security Implications
- Frontend role checks are for UX only; Spring Security continues to protect owner and customer endpoints.
- Use HTTPS in production; never log the password or bearer token.
- Token revocation and refresh are not currently implemented.

## Testing
Authentication API tests cover OWNER/CUSTOMER role selection from `/auth/me`, login response parsing, input validation, invalid credentials, bearer usage, token storage lifecycle, and 401/403/network distinctions. Customer profile API tests cover the `/api/customer/me` contract, response validation, 401/403, and network error messaging. In this phase, frontend tests, production build, and lint are run; no new live browser-to-backend verification is claimed.

## Future Implications
Revisit token storage and renewal before production deployment with broader threat-model requirements. Customer-scoped records, subscriptions, and issued-bill APIs are implemented. The proposed payment ledger is documented in ADR-010; implement financial views only after owner decisions and backend APIs are complete.

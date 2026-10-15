# ADR-012: Customer Self-Registration with Owner Approval

## Decision
Allow visitors to create their own CUSTOMER account through `POST /api/auth/register`. New accounts start
with Customer status `PENDING` and the owner approves them by setting the status to `ACTIVE`.

## Status
Accepted (implemented)

## Context
Before this change, only the OWNER could create customer accounts (`POST /api/owner/customers`, ADR-005), and
the owner chose each customer's initial password. Customers could not sign up themselves. That is a core
product gap: a new customer had to ask the owner to type in their details.

The business has no SMS provider. Phone numbers therefore cannot be verified at registration time.

## Decision
- Public endpoint `POST /api/auth/register`, next to `POST /api/auth/login`, using the same JWT/BCrypt stack.
  No second authentication system.
- Request fields: `phone`, `password`, `name`, optional `address`. The request has **no** role, ID, status,
  billing type, or join date field. Extra JSON fields are ignored, so `"role": "OWNER"` has no effect.
- The server always creates role `CUSTOMER`, status `PENDING`, billing type `MONTHLY`, and join date = today
  in Asia/Kolkata.
- User and Customer are created in one transaction by reusing `CustomerService`'s creation path (ADR-005).
- Success returns `201` with the same body as login (`token`, `userId`, `phone`, `role`), so the customer is
  signed in immediately and the frontend reuses its existing session check.
- `PENDING` is a new `CustomerStatus` value. Subscriptions and daily records already require `ACTIVE`, so a
  pending customer cannot receive supply or bills until approved. The customer can still sign in and see
  their own (empty) workspace.

## Why We Chose It
- Without phone verification, anyone can claim any number. An approval step means a fake or mistaken account
  cannot affect supply or billing until the owner has checked it.
- `PENDING` was chosen over reusing `INACTIVE`, which means "former customer", so the owner can tell new
  sign-ups apart from customers who stopped.
- Returning a token immediately avoids asking someone to type the password they just chose a second time.

## Alternatives Considered
- **SMS one-time code:** proves phone ownership, but adds a paid external provider, a secret, and delivery
  failure handling. Deferred until the business decides it is worth the cost.
- **Register as ACTIVE:** simplest, but spam or mistaken accounts would immediately be treated as real
  customers and appear in billing.
- **Register as INACTIVE:** no enum change, but mixes new sign-ups with former customers.
- **Redirect to sign-in after registration:** an extra step with no security benefit.

## Advantages
- No database migration: `customers.status` is `VARCHAR(20)` with no check constraint.
- The owner keeps control of who is supplied and billed.
- One shared creation path for owner-created and self-registered customers.

## Disadvantages / Trade-offs
- **Phone squatting:** someone can register a real customer's number first. The owner's create then fails
  with 409. The owner can see the PENDING account and contact the person. Account deletion and owner password
  reset are not implemented yet.
- **Account enumeration (accepted deliberately):** a 409 "already registered" tells a visitor that a number
  has an account. The owner chose a clear message over a vague one, because customers of all ages use the
  app and need to know to sign in instead.
- **Rate limiting (added 2026-10-15):** see "Rate limiting" below.

## Security Implications
- The role is never read from the request; it is fixed in `CustomerService.registerCustomer`.
- Passwords: at least 8 characters (same as owner-created customers) and at most 72 UTF-8 bytes (BCrypt's
  limit), hashed with the existing `BCryptPasswordEncoder`.
- The response never contains the password or its hash.
- Duplicate phones are rejected by a pre-check and, for concurrent requests, by the `uk_users_phone` unique
  constraint. The insert is flushed so the violation is translated to the same 409 instead of a 500.
- The existing OWNER account cannot be overwritten or taken over: its phone returns 409 and nothing changes.

## Rate limiting
Implemented in `AuthRateLimiter`, with limits configurable under `milkweb.rate-limit.*`:

| Limit | Default | Keyed by |
|---|---|---|
| Failed logins | 5 per 15 minutes | phone number |
| Failed logins | 30 per 15 minutes | client IP |
| Registration attempts | 10 per hour | client IP |

- Over a limit, the API returns `429` with `Retry-After` (seconds) and
  `{ "error": "Too many attempts. Please wait N minutes and try again." }`. A blocked login is rejected before
  the password is checked, so the correct password does not help until the window ends.
- A successful login clears that phone's failure counter.
- **Client IP** is the first `X-Forwarded-For` entry. Render staff have stated it is the real client IP, but
  a client may be able to forge it. Per-IP limits are therefore only used where a bypass is tolerable: the
  per-phone login limit does not depend on IP, and extra registrations stay PENDING.
- **Owner lock-out trade-off:** anyone who knows the owner's phone can trigger a 15-minute login pause by
  entering wrong passwords. No password is revealed, and the pause ends on its own. This is the standard
  trade-off of per-account limits.
- **In memory, one instance:** counters reset on restart and are not shared between instances. That is
  enough for the current single Render instance. At most 50,000 keys are tracked; beyond that, new keys are
  not limited (fail open) and a warning is logged, so a flood of fake keys cannot exhaust memory.
- No new dependency (no Bucket4j, no Redis).

## Performance Implications
One BCrypt hash per registration (deliberately slow, like login). Negligible at this scale.

## Future Implications
- SMS verification, owner-side account deletion, and password reset are the natural next steps if abuse
  appears. A shared store (for example Redis) is needed for rate limits if the backend ever runs more than one
  instance.
- Mobile clients can use the same endpoint without changes.

## Interview Takeaway
Public sign-up is a privilege boundary. Fix the role on the server, never trust client-supplied identity or
state, let the database constraint be the final word on uniqueness, and when you cannot verify identity,
limit what an unverified account can do (here: nothing is supplied or billed until the owner approves).

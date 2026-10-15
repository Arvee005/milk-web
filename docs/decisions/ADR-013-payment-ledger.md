# ADR-013: Payment Ledger with Automatic Oldest-First Allocation

## Status
Accepted (backend implemented 2026-10-15; owner screens follow). Builds on the proposal in ADR-010 and the owner
decisions recorded in `docs/financial-policy-decisions.md`.

## Context
Bills could be issued but nothing recorded whether they were paid. The owner approved these rules: payments
clear the oldest unpaid bill first; leftover money is credit applied automatically to the next bill; money
returned is recorded and can never exceed the credit; nothing is edited or deleted; amounts are INR; methods are
Cash and UPI.

## Decision
Immutable event tables added by Flyway `V4__create_payment_ledger.sql` (no existing table changed):

| Table | Meaning |
|---|---|
| `payments` | Money received: amount, INR, Cash/UPI, optional UPI reference, owner note, date received, who recorded it |
| `payment_reversals` | A payment entered in error, with a reason. The payment row stays. |
| `account_adjustments` | Owner credit or extra charge with a reason. On a bill it changes what the bill asks for; without a bill it is a credit held for later. |
| `refunds` | Money the farm gave back out of credit |
| `bill_allocations` | Credit applied to a bill (ALLOCATE) or taken back after a reversal (RELEASE), with the event that caused it |

Balances are calculated, never stored:

```
bill amount to pay = bill total + charges on it − credits on it
bill due           = amount to pay − net credit applied to it               (never negative)
customer credit    = active payments + account credits − refunds − net credit applied  (never negative)
```

One routine (`LedgerService.applyCredit`) applies all available credit to unpaid issued bills, oldest billing
month first. It runs after a payment, after an adjustment, and inside bill issuing. A reversal records RELEASE
rows against the newest bills until credit is no longer negative; if the payment's money was already returned,
the reversal is refused and rolled back.

## Why
- Explicit allocation rows keep an audit trail of which money paid which bill, and they do not shift when later
  events happen (unlike recomputing first-in-first-out on every read).
- A customer-level credit pool (rather than per-payment remainders) keeps refunds and reversals simple and is
  enough because allocation is automatic.
- Calculated balances cannot drift from their history.

## Alternatives considered
- **Owner picks bills for each payment** (ADR-010's default): rejected by the owner in favour of automatic
  oldest-first. The screen still previews exactly which bills a payment will clear.
- **Recompute allocation on every read:** no allocation table, but past entries could appear to move if an
  older bill were issued later. Rejected for auditability.
- **Stored balance columns on Customer/Bill:** fast, but can drift and cannot explain themselves. Rejected.

## Safety
- Every write locks the customer row (`SELECT … FOR UPDATE`) first. Bill issuing locks the bill and then the
  customer; ledger writes never lock bills, so the lock order cannot deadlock. A test that removes the lock fails.
- Money-changing requests need an `Idempotency-Key` header (8–64 characters). Repeating a key with the same
  request returns the original result; a different request is refused (409). The CORS allow-list includes the
  header.
- Database checks: positive amounts, INR only, Cash/UPI only, account-level rows can only be credits.
- Only OWNER can write. Customers can read only their own account and history (derived from the signed-in user;
  a supplied `customerId` is rejected), and never see owner notes or who recorded an entry.

## Limits and follow-up
- Account-level extra charges are not supported (charges attach to a bill).
- Adjustments are not reversed by linkage; a mistaken adjustment is offset by an opposite one with a reason.
- `accountsWithBalances` calculates per customer, which is fine for a small dairy; add a summary query if the
  customer count grows large.
- Existing bills do not store a currency column; the owner confirmed they are INR.

## Interview takeaway
Model money as immutable facts plus explicit allocations, derive balances, and serialise writes per account.
Prove the concurrency control with a test that fails when the lock is removed.

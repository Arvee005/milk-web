# ADR-010: Customer Financial Ledger and Bill Allocations

## Status
Proposed - architecture recommendation for owner review. No application code or schema is implemented.

## Context
Milk Web currently records chargeable DailyRecord amounts, generates DRAFT monthly Bills from closed periods, and allows an OWNER to issue them. Bills have immutable financial snapshots and explicit issue metadata, but no currency field. There are no payment, payment allocation, adjustment, refund, or customer balance records. `BillingType.PREPAID` only excludes a Customer from monthly Bill generation; it does not represent money or credit.

The product needs an auditable way to distinguish amounts invoiced, money received, money assigned to bills, unapplied customer credit, corrections, and remaining balances. Some payments may arrive before bills or exceed one bill. A bill-only payment field cannot represent those cases without losing money's account-level state.

## Decision proposal
Use a customer-account model backed by immutable financial events:

- Keep the issued Bill as the source of an invoiced obligation.
- Store an actual receipt separately as a Payment/PaymentReceipt attributed to an OWNER and customer.
- Store explicit PaymentAllocation events separately, allowing a receipt to cover multiple issued Bills and a Bill to receive multiple receipts.
- Derive unallocated cash credit from received amount minus net allocations (refunds remain out of scope).
- Store non-cash BalanceAdjustments separately, with customer or bill scope, direction, actor, reason, and compensating corrections.
- Derive bill outstanding and customer account due/credit from these sources. Do not make mutable Customer/Bill balance columns authoritative.
- Use bounded read-only customer summary/statement endpoints; derive Customer from the authenticated User ID.
- Prefer one owner-configured currency initially, but persist currency snapshots on Bills and financial records after the operating currency and legacy-bill interpretation are explicitly confirmed.

The owner workflow may offer a per-bill shortcut, but it must still create a receipt and explicit allocation. The first implementation should not silently allocate to other bills.

## Alternatives considered

### Mutable balance fields on Customer and Bill
Fast to query and initially simple, but concurrent updates and correction workflows can drift from payment history; a balance alone cannot explain what was received or allocated. Rejected as authoritative state.

### Customer account with explicit immutable events (selected proposal)
Supports prepayments, excess payments, partial/multiple bill allocations, audit, and future mobile statements. It requires aggregate reads, transactional validation, idempotency, and locks; summaries can be cached later as rebuildable projections. Recommended for the product.

### Full double-entry general ledger
Provides formal accounting controls but adds chart-of-accounts, posting, reconciliation, and accounting policy well beyond the present product's requirements. Not recommended unless later business, tax, or external accounting needs justify it.

## Balance conventions

- Positive amounts are stored on receipt/allocation/adjustment source records; direction is explicit for adjustments.
- A positive net customer position means amount due; a negative position means customer credit.
- Bill outstanding = issued total + bill debits - bill credits - net allocations, and must not be negative.
- Customer net due = sum of bill outstanding + account debits - account credits - unallocated cash received.
- Expose amount due and account credit as separate non-negative figures. Report unallocated cash separately from adjustment-derived credit.
- Excess receipts remain unallocated; allocation does not create or destroy cash.

## Consequences

### Positive
- Provides auditable evidence of received funds separate from invoices and adjustments.
- Handles advance and overpayments without fabricating a bill payment.
- Makes correction append-only and supports future account statements for mobile clients.
- Preserves existing Bill and DailyRecord snapshots instead of recalculating history.

### Costs and risks
- Requires schema migration support, currency confirmation, transaction-safe aggregate validation, idempotency, and PostgreSQL concurrency tests.
- Requires owner decisions on allocation priority, receipt corrections/refunds, bill correction/voiding, adjustment approvals, and treatment of historical currency.
- On-demand account summaries are more complex than reading a mutable balance; any future cached summary must be rebuildable and reconcilable.

## Unresolved decisions

Operating currency and legacy Bills; whether PREPAID customers hold account credit; explicit versus automatic allocation; payment correction and refund policy; issued-bill correction/void policy; balance-adjustment reasons/approvals and over-credit handling; payment date/method semantics.

See [payment-domain-design.md](../payment-domain-design.md) for the full scenario analysis, proposed APIs, invariants, and test matrix.

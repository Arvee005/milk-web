# Payment and Balance Domain Design

## Status and terminology

This is a design proposal only. No payment entities, APIs, migrations, or balance behavior have been implemented. Existing customer bills and issued-bill visibility are separate completed work.

Owner-facing policy questions and an approval checklist are in [Financial Policy Decisions](./financial-policy-decisions.md). That guide records options and recommended defaults, but none are approved until the owner confirms them.

Labels used below:
- **Confirmed existing behavior**: verified in current code or schema mapping.
- **Recommendation**: proposed design, not current application behavior.
- **Owner decision required**: a business policy that must be confirmed before implementation.

## Executive recommendation

Model money as a customer account with an immutable receipt ledger and explicit allocations to issued bills. Keep the receipt, its bill allocations, and balance adjustments as separate records. Do not store authoritative mutable balances on Customer or Bill; calculate them from posted events and optionally maintain rebuildable summaries after measuring performance.

This supports ordinary per-bill collection as a convenient workflow while correctly representing advance and excess cash as unallocated customer credit. It does not require automatic allocation, online payment processing, tax accounting, or a general-purpose double-entry ledger.

Initial implementation should use manual receipts recorded by an OWNER. A receipt may be allocated to one or more eligible issued bills in the same transaction; any unapplied remainder stays unallocated. A receipt recorded without bill allocation remains account credit. Allocation order should be explicit, not silently inferred.

## Existing financial behavior

### Confirmed existing model

- `User` stores the login identity, BCrypt password hash, and CUSTOMER/OWNER role. Each `Customer` has a required, unique User relationship. Existing customer endpoints use the authenticated principal's User ID to resolve the Customer; proposed customer financial reads must preserve this pattern.
- `Customer.billingType` is `PREPAID` or `MONTHLY`. New customers default to MONTHLY if no type is provided.
- The only billing behavior that branches on this field is Bill generation: it accepts MONTHLY customers and rejects PREPAID customers. PREPAID does not create a wallet, collect money, record credit, block service pending payment, or calculate a balance.
- DailyRecord stores actual collected quantity, a historical unit-price snapshot, and a server-calculated amount. COLLECTED/EXTRA are chargeable; SKIPPED/PAUSED have zero amount. Quantity and price use `BigDecimal`; the product is rounded HALF_UP once to scale 2 and stored as `NUMERIC(18,2)`.
- A BillingPeriod is an inclusive calendar month. Closing is explicit and irreversible in the current workflow; period locking prevents later DailyRecord writes.
- A closed period does not create a bill. For a MONTHLY customer with nonzero chargeable records, generation creates one DRAFT bill per customer/period, with a unique customer-period constraint and immutable line snapshots.
- An OWNER explicitly issues a DRAFT. ISSUED requires both `issuedAt` and `issuedBy` by a database check constraint. There is no bill edit, reopen, or void operation.
- Customer-facing bill reads expose only ISSUED bills. ISSUED means finalized/issued under the current product workflow; it does not mean paid.
- `Bill.totalAmount` and bill-line amounts are `NUMERIC(18,2)`. BillLine quantity is `NUMERIC(8,3)` and unit price is `NUMERIC(12,2)`. No monetary field stores a currency code.
- Payment, allocation, credit, debit, refund, and BalanceAdjustment data do not exist. No customer or bill balance can currently be computed reliably.
- No Flyway/Liquibase dependency or migration scripts were found. Development uses H2 `create-drop`; PostgreSQL is declared as a runtime dependency/intended production database. A production schema migration process must be selected before adding financial tables/columns.

### Incomplete or conflicting financial assumptions

- PREPAID sounds like payment behavior but currently means only "not eligible for MONTHLY bill generation." It must not be used as evidence of cash received, a zero balance, or available credit.
- Existing Bills do not establish a currency. INR is mentioned as an assumption in documentation, not as persisted or confirmed data. Historical amounts must not be silently labelled INR or another currency.
- Current code has no correction path for issued bills. Because bill issue is irreversible, financial adjustments must not edit issued bill totals or line snapshots in place.
- The current Bill generation query uses the customer's current billing type, not a billing-type snapshot taken at period close. No payment feature should infer historical eligibility differently without an explicit migration/product rule.

## Proposed concepts and relationships

```text
Customer
  1 -- * PaymentReceipt
  1 -- * BalanceAdjustment (customer-scoped)
  1 -- * Bill (existing)

PaymentReceipt
  1 -- * PaymentAllocationEvent -- 0..1 Bill (existing, ISSUED only)

Bill (existing issued obligation)
  1 -- * PaymentAllocationEvent
  1 -- * BalanceAdjustment (bill-scoped, optional)

CustomerAccountSummary (derived projection; not initially authoritative storage)
BillBalance (derived projection; not initially authoritative storage)
```

### Payment receipt

**Recommendation:** Create a `Payment`/`PaymentReceipt` record for a payment actually received, independent of its allocation. Suggested fields:

- ID, required Customer FK, positive amount, currency code.
- `receivedAt` (when the funds were received, if known), `recordedAt` (server time), optional method (`CASH`, `BANK_TRANSFER`, `OTHER`), optional external reference/note.
- Required `recordedBy` User FK for OWNER attribution.
- A persisted idempotency key and request fingerprint for retry-safe recording.
- Optional immutable correction/reversal linkage only after the owner approves the relevant policy.

A receipt means money was actually received. It is not an invoice, discount, balance adjustment, or payment intent. A manual bank-transfer reference is evidence metadata, not proof that the transfer settled; the owner workflow should record only funds confirmed received.

### Payment allocation

**Recommendation:** Model allocations separately because one receipt may be split among several bills, and a bill may be paid by several receipts. A receipt may also remain partly or wholly unapplied.

An allocation event should identify payment, bill, positive amount, currency, actor, timestamp, and idempotency/event identity. Only bills belonging to the same Customer, already ISSUED, and in the same currency are eligible.

Allocation and deallocation should be append-only events. Reallocation should append a release/reversal for the old allocation and a new allocation; it must not rewrite the original event. A release must reference the event it reverses and cannot exceed that event's still-active amount.

### Unallocated credit

Unallocated credit is a derived amount, not a second receipt:

`unallocated cash = received payment total - net active allocations - confirmed cash refunds`

Refunds are out of scope and the equation's refund term remains zero until a refund model is approved. A payment recorded before a bill is issued, or without an allocation, remains unallocated. It may be explicitly applied later after an eligible bill exists.

Do not count a receipt both as bill payment and as unallocated credit. Allocation changes distribution between these views but do not change total cash received.

### Balance adjustment

**Recommendation:** Keep `BalanceAdjustment` distinct from Payment. An adjustment changes an obligation/credit position without claiming that money moved.

Suggested fields: Customer FK, optional Bill FK (null means account-level), direction (`DEBIT` or `CREDIT`), positive amount, currency, reason code and human-readable explanation, effective/recorded timestamps, actor User FK, and optional `reversalOf`/correction reference.

Adjustments are immutable after posting. A correction is a new compensating adjustment with a required reference to the original and an explanation. A bill-scoped adjustment is included in that bill's balance. A customer-scoped adjustment affects the account net position, not an individual bill allocation.

**Owner decision required:** Whether a bill credit greater than the remaining bill balance is rejected, capped, or requires explicit conversion of the excess to an account-level credit. Recommended safe default: reject the excess and have the OWNER record a separate account-level credit after confirmation.

### Bill balance and customer account position

These are calculated views, not independent source records:

- **Bill outstanding** = issued bill total + bill-scoped debit adjustments - bill-scoped credit adjustments - net payment allocations to that bill.
- **Customer net amount due** = sum of bill outstanding amounts + customer-scoped debit adjustments - customer-scoped credit adjustments - unallocated cash credit.
- Positive customer net amount due means the customer owes money. Negative means the customer has net credit. Display `amountDue = max(net, 0)` and `accountCredit = max(-net, 0)` rather than exposing a confusing signed amount alone.
- **Available unallocated cash credit** is reported separately from a credit adjustment. It represents received cash not currently assigned to a bill; it is not another name for all account credit.
- Bill outstanding must never be negative. Any excess receipt remains unallocated customer credit rather than overpaying a bill.

Customer-level credit adjustments may make net account due smaller or produce account credit without being a payment. Report adjustment credits separately in transaction history; do not describe them as money received.

## Allocation and correction rules

| Scenario | Recommended behavior | Decision still needed |
|---|---|---|
| Exact payment for one issued bill | Record receipt and one allocation in one transaction; bill outstanding becomes zero. | None beyond confirming the account-based model. |
| Partial payment | Allocate only received amount; retain positive bill remainder. | Whether the owner may record a payment date different from the current date. |
| Overpayment | Record full received amount; allocate at most the bill remainder; leave excess as unallocated account credit. | Whether to offer this default automatically in the UI; do not silently apply to other bills. |
| Payment before any bill is issued | Record receipt as unallocated credit; permit explicit later allocation to an issued bill. | Whether monthly/prepaid customers can both hold credit and whether service access depends on credit. |
| Multiple outstanding bills | Keep distinct bill balances. OWNER chooses allocation targets. | Whether to offer a previewed oldest-first suggestion; automatic application must be explicitly approved. |
| One payment split across bills | Accept an allocations list; validate each bill and aggregate amount atomically. Remainder stays unallocated. | Whether initial UI should support multi-select or require later allocation operations. |
| Owner records payment without bill | Record a valid receipt with zero allocation; show resulting unallocated credit. | Confirm that such receipts are permitted for all billing types. |
| Incorrect payment amount/date/reference | Never edit/delete a posted receipt. Append a correction/reversal and, if money was truly received, a replacement receipt. Preserve actor, time, original, reason. | Define who may reverse, whether owner approval is required, and how an actual cash refund differs from a data correction. |
| Credit/debit correction | Record a separate immutable adjustment, not a payment. Require reason and actor. | Define allowed reason codes, approval thresholds, and bill-vs-account scope. |
| Issued bill corrected after payment | Do not mutate issued bill or lines. Record a traceable correcting credit/debit against bill/account and issue a replacement/corrected obligation only through an approved workflow. | Choose void/reissue vs adjustment/credit-note semantics; decide treatment of existing allocations and customer notifications. No policy is assumed here. |
| Negative customer net due | Display zero amount due and a separate account-credit figure, retaining the source transactions. | Decide whether credit can be refunded, carried forward, or only applied to future bills. Refunds are not included in this design. |

### Avoiding duplicate entries and over-allocation

- Require an idempotency key for receipt creation. Same key plus same canonical request returns the original result; same key with a different request conflicts.
- Add unique constraints for the idempotency scope and, where trustworthy, a normalized external payment reference scoped to the provider/method. A reference is optional for cash and cannot prove that two same-amount cash receipts are duplicates.
- For each Payment, `0 <= net allocated <= amount received` (excluding separately modelled refund events).
- For each Bill, `0 <= net allocations <= issued amount + eligible bill-scoped debit - bill-scoped credit`.
- All source payment and target bill records must belong to the same Customer and currency.
- Validate and post receipt plus initial allocations atomically. A failed allocation must not leave a partially persisted receipt unless the API explicitly returns a receipt with no allocation by design.
- Reallocation is a new compensating event sequence, not an update. Ensure both the released and new allocation effects are committed or neither is.

## Ledger approaches compared

| Approach | Auditability and correction | Consistency | Query/performance | Complexity/mobile suitability |
|---|---|---|---|---|
| Mutable balance fields on Customer and Bill | Weak: edits erase how the amount was reached; correction history must be rebuilt separately. | Easy to drift when payment/allocation/bill updates fail independently or race. | Fast reads, but every write must update several counters correctly. | Initially simple; difficult to explain and reconcile; API returns opaque totals. |
| Immutable source events plus payment allocations; derive summaries | Strong: receipts, allocations, and adjustments remain attributable and can be reversed/compensated. | One transaction plus database constraints/locking protects source of truth; balances are reproducible. | More joins/aggregations; can be indexed and paged. Cache/projections may be added as rebuildable optimizations. | Moderate implementation cost; clear statement/event contracts suit web and future mobile clients. |
| Full double-entry accounting ledger | Strongest formal accounting controls. | Strong if every posting is balanced and all accounting rules are implemented. | More complex posting/reconciliation queries. | Unnecessary accounting/tax scope for current dairy needs; substantial implementation and operational burden. |

**Recommendation:** Use the middle option. Treat issued Bills as obligation facts; Payments as received-money facts; allocations as distribution events; adjustments as non-cash account corrections. Do not add a general-purpose chart-of-accounts or double-entry general ledger for this product phase.

### On-demand versus stored balances

Start with on-demand summaries from the authoritative event rows, using aggregate queries and indexes by customer, bill, and event time. Store no mutable balance on Customer or Bill. Measure real query cost before adding a projection.

If volume later requires a materialized summary, store it as a rebuildable projection with a source sequence/version and a reconciliation job. It must be possible to recompute it from the immutable records, and APIs/tests must compare projections against the authoritative calculation. A projection is never the only evidence of money received or owed.

## Proposed API contracts (not implemented)

All endpoints below are proposals. Existing OWNER APIs remain OWNER-only under `/api/owner/**`; customer financial reads remain read-only under `/api/customer/**`. All money-changing routes require an authenticated OWNER and actor attribution. Customer routes derive Customer from authenticated User ID; a customer ID supplied in body/query is rejected, not trusted.

### Owner operations

#### Record receipt, optionally with explicit allocations

`POST /api/owner/customers/{customerId}/payments`

Headers: required `Idempotency-Key`.

Request proposal:

```json
{
  "amount": "125.00",
  "receivedAt": "2026-10-03T12:00:00Z",
  "method": "CASH",
  "reference": null,
  "allocations": [
    { "billId": "issued-bill-uuid", "amount": "100.00" }
  ]
}
```

Response: `201 Created` with receipt ID, customer ID, amount/currency, method/reference, received/recorded timestamps, recorded-by actor ID, allocated total, unallocated remainder, and allocation summaries. It does not claim provider settlement beyond owner-recorded receipt.

With the recommended single configured currency, the server derives currency from configuration and returns it; the client cannot select a currency. Validation: positive amount with exact supported scale; allocations positive and sum no greater than receipt; every bill belongs to this customer, is ISSUED, has resolved matching currency, and has enough remaining balance; references and idempotency key have bounded length. Repeated same key/request returns the original receipt; key reuse with different canonical request returns `409 Conflict`. Cross-customer bill IDs and non-issued bills fail without partial writes.

Alternative workflow: `POST /api/owner/payments/{paymentId}/allocations` can apply previously unallocated funds. Reallocation/reversal needs an approved correction policy and must append compensating allocation events; it must never rewrite history.

#### Record adjustment

`POST /api/owner/customers/{customerId}/balance-adjustments`

Request proposal: positive `amount`, direction `DEBIT|CREDIT`, reason code, explanation, and optional `billId`. With the recommended single configured currency, the server derives currency from configuration and returns it; the client cannot choose a currency. Actor and recorded timestamp are server-derived. Adjustment is customer-scoped when `billId` is absent; otherwise the bill must belong to the path customer, be ISSUED, and have resolved matching currency. Require idempotency to make retries safe. Return the adjustment and its resulting summary; the adjustment is not represented as cash.

#### Owner summaries and history

- `GET /api/owner/customers/{customerId}/account`: due, account credit, total issued obligations, total received, allocated, unallocated cash, adjustment totals, currency, and as-of timestamp.
- `GET /api/owner/customers/{customerId}/transactions?page=0&size=50`: one chronological statement of issued bills, receipts, allocation/release events, and adjustments. Stable sort `effectiveAt DESC, id DESC`; page size 1-100.
- `GET /api/owner/customers/{customerId}/bills/{billId}/balance` or add balances/allocations to the existing owner Bill DTO only after the response contract is deliberately versioned/evolved.

Errors: 400 invalid amount/currency/idempotency or pagination; 401 unauthenticated; 403 non-OWNER; 404 unknown customer/bill outside that customer; 409 duplicate idempotency mismatch, non-issued bill, insufficient available receipt, over-allocation, or conflicting concurrent state.

### Customer read operations

- `GET /api/customer/account`: read-only account summary with amount due, account credit, unallocated cash credit, currency, and as-of timestamp. Resolve the Customer only from the principal.
- `GET /api/customer/transactions?page=0&size=20`: paged read-only statement, stable `effectiveAt DESC, id DESC`, maximum size 100. Include event type, date, signed account impact, description/reference safe for customer display, and related bill period/ID where appropriate; do not expose owner actor IDs or internal notes.
- `GET /api/customer/bills/{billId}`: read-only detail scoped in the repository by both authenticated Customer and bill ID. Include issued amount, remaining bill balance, allocations, period, and immutable bill lines. Outside-customer ID returns 404 (or the established non-disclosure policy).
- Existing `GET /api/customer/bills` can be evolved to include bill balance/allocated summary with a reviewed DTO contract; do not use client-supplied customer IDs.

Customer APIs do not record, allocate, reverse, refund, or adjust money. Do not include `paid=true/false` until policy defines whether that means full bill allocation, cleared funds, or another state. Prefer amounts such as `allocatedAmount` and `remainingAmount` with precise definitions.

Pagination uses bounded server defaults/maxima and stable secondary ID sort. Customer queries must constrain every entity query by resolved Customer ID, not merely filter rows in frontend code.

## Currency and precision

**Owner decision required:** confirm the currency of the business and existing bill amounts. INR must not be inferred from examples or locality.

Options:
1. **One configured currency** (recommended for current single-farm scope): configure an ISO 4217 code centrally, persist the code on each new Bill and each financial receipt/allocation/adjustment, reject mismatches, and do not implement FX. Persisting a currency snapshot keeps historical meaning explicit even while configuration is single-valued.
2. **Currency per customer/account:** allows distinct account currencies but requires currency-specific totals and prevents cross-currency allocation. More state and support burden.
3. **Multi-currency/FX:** requires exchange rates, conversion timestamps, rounding, realized/unrealized differences, and settlement policy. Not justified now; do not implement.

Before applying a currency to existing Bills, obtain explicit owner confirmation of what their amounts mean. A migration must preserve the original numeric values and record how legacy rows were classified. If no reliable interpretation exists, mark legacy bills' currency unresolved and block financial allocation to them until reconciled; do not backfill a guessed code.

Continue using `BigDecimal`, never binary floating point. Existing charge calculations round HALF_UP once to two decimals. Recommended initial payment/adjustment/allocation inputs must be exact scale 2 and rejected (not silently rounded) when they contain unsupported fractional precision. Use a precision at least as large as the bill's `NUMERIC(18,2)` and validate bounds before persistence. Confirm chosen currency's minor unit before locking that scale; the current model effectively assumes two-decimal monetary amounts but does not establish a currency. A future non-two-decimal currency requires an explicit schema/API precision decision.

## Security, audit, and concurrency

- Only OWNER may record receipts, allocations, reversals, or adjustments. Customers receive read-only account/statement/bill-balance APIs. No customer-initiated payment mutation or online payment provider is part of this proposal.
- Every event records server-authenticated actor ID and server timestamp. Client-supplied `recordedBy`, role, customer identity, or resulting balance is never accepted.
- Customer resources resolve through User ID -> Customer and every query, including line/allocations, includes that Customer constraint. A customer ID in input cannot authorize access.
- Posted receipts, allocation events, and adjustments are append-only. Database permissions/API design should avoid physical update/delete. Corrections reference original records and preserve actor, time, and reason.
- Use a single database transaction for receipt plus initial allocations or adjustment plus its audit linkage. Acquire locks consistently (e.g. Customer account row first, then Payment and target Bills in sorted UUID order) before validating aggregate availability and inserting events.
- Serialize concurrent allocation against the same receipt or bill. A customer-row lock is a simple initial per-account mutex but may cause contention; an account row/versioned aggregate can be added if measured. Do not rely only on a pre-insert sum query under ordinary isolation.
- Idempotency constraints protect HTTP retries; unique provider references protect supported transfer methods where reference uniqueness is reliable. Manual cash duplicate detection still requires operator controls/reconciliation.
- Test concurrency against PostgreSQL, because existing H2 tests do not establish PostgreSQL lock/isolation behavior.
- Do not issue financial events outside a transaction. Use outbox/audit delivery only if later external notifications or integrations need reliable publication.

## Owner decisions required before implementation

Use [Financial Policy Decisions](./financial-policy-decisions.md) to review these items and record explicit approval. Its recommendations are not policy until approved.

1. Select the operating currency and confirm the currency represented by all already-issued bills; define migration treatment for unresolved historical rows.
2. Confirm customer-account credit as the operating model. Recommendation: yes, with per-bill allocations and unallocated credit; allow a "record against this bill" shortcut that still creates a receipt plus allocation.
3. Choose manual allocation only versus an owner-confirmed oldest-first suggestion/automatic policy. Recommendation: explicit allocation in v1; never silently spread a payment.
4. Confirm whether an unallocated payment is allowed for PREPAID and MONTHLY customers, and whether PREPAID is only a pricing/collection category or has future service eligibility behavior.
5. Define receipt correction/void authorization, reason requirements, approval threshold, and distinction between correcting data and refunding real money.
6. Define bill correction/void/reissue after issue, including existing allocations and customer notice. Until then, preserve issued Bills and use no ad-hoc mutation.
7. Define allowed adjustment reasons, scope, approvals, and treatment of a credit larger than a bill remainder.
8. Decide what "payment date" means operationally, which methods are recorded, and whether bank transfer is entered only after confirmation of settlement.
9. Confirm retention/access policy for payment references and customer-facing statement descriptions. Do not include sensitive bank account/card details.

## Recommended implementation sequence (future code phase)

1. Confirm decisions 1-4, select/enable the production migration tool/process, and design explicit legacy-bill currency reconciliation. Do not start schema work before this gate.
2. Add schema constraints and immutable receipt/allocation/adjustment records, currency columns/snapshots, idempotency keys, actor FKs, indexes, and transaction/locking strategy. Backfill only owner-confirmed historical currency; retain unresolved rows safely.
3. Implement owner receipt entry with optional explicit allocations and unallocated-credit support; add idempotency and PostgreSQL concurrency tests.
4. Implement owner customer statement, summaries, bill balances, and append-only adjustment/correction paths only after decisions 5-7.
5. Implement customer read-only statement/account/bill allocation views with principal-derived customer scoping and privacy review.
6. Add customer workspace screens that distinguish invoices, cash received, allocated amounts, unallocated credit, adjustments, amount due, and account credit. Do not show payment state inferred from issued status.
7. Run reconciliation checks against synthetic event histories, verify database invariants, and compare any cached projections with on-demand balances before release.

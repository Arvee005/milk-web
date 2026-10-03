# Financial Policy Decisions

## Purpose and status

This guide is for the farm owner to confirm the business rules needed before Milk Web implements payment and balance functionality. It translates the proposed design in [Payment and Balance Domain Design](./payment-domain-design.md) and [ADR-010](./decisions/ADR-010-customer-financial-ledger.md) into operational choices.

**Every recommendation below is pending owner approval. Nothing in this guide is an approved farm policy or implemented behavior.** No payment functionality exists yet.

Related current-state references: [database design](./database-design.md), [API design](./api-design.md), [security](./security.md), and [architecture](./architecture.md).

The current system stores bill amounts but no currency, payments, allocations, refunds, adjustments, or balances. Bills are created as DRAFT and explicitly issued by an OWNER. `ISSUED` means issued, not paid. `PREPAID` currently only excludes a customer from monthly bill generation; the software does not implement prepaid collections or customer credit.

## Decisions that depend on real-world evidence

Before choosing defaults, consult records or the people who handle collections:

- Existing bills, price lists, receipts, bank statements, cash books, and customer messages are needed to establish the currency of historical amounts.
- Current practice is needed to establish whether PREPAID customers pay before collection, how advance cash is handled, whether overpayments carry forward, and whether payments are assigned to a specific month.
- Cashbooks, bank statements, and transfer references can show which payment methods are actually used and what the farm considers "received."
- Past corrections, returned cash, customer disputes, or reissued invoices will clarify whether refunds and bill corrections are rare exceptions or routine work.

Do not infer policy from an enum name, project example, customer location, or an undocumented assumption. If old records do not establish a fact, record it as unknown and keep those financial records unresolved rather than silently assigning a value.

## Owner decisions

### 1. Operating currency

**Existing behavior:** Bills, bill lines, DailyRecords, and product prices store decimal amounts, but no currency code or configuration. The API and customer Bills view do not claim a currency. INR has appeared as an assumption in notes, not as a confirmed setting.

**Plain-language question:** What currency does the farm actually use for all customer prices, bills, and receipts?

**Options and consequences:**
- **One currency for the farm (recommended default):** simplest for one local dairy; all new bills and financial entries use the confirmed currency, and mixed-currency entries are rejected. Historical values still need a separate review.
- **Currency chosen per customer:** useful only if the farm genuinely quotes or collects different currencies; comparisons and balances must then stay separated by currency.
- **Multiple currencies with conversion:** requires exchange-rate records, conversion dates, rounding policy, and additional reconciliation. This is unnecessary unless cross-currency business is real.

**Recommended default for a small dairy:** Use one owner-confirmed currency, store its code on new financial records and bills, and do not support foreign exchange.

**Pending owner approval:** Confirm the currency code and whether any current customer prices or receipts are in another currency. This depends on actual price lists and collection records and blocks financial implementation.

### 2. Historical bills without currency

**Existing behavior:** Existing bills have numeric totals and line amounts but no currency field. The software cannot establish what a historical amount represents.

**Plain-language question:** Can the farm reliably identify the currency used for every old bill, or are some bills ambiguous?

**Options and consequences:**
- **Confirm one currency for every historical bill from reliable records:** allows those bills to be included in balances after a documented review. A blanket confirmation is appropriate only if evidence supports it.
- **Classify bills individually or by a documented period/customer group:** preserves supported history while marking uncertain records unresolved; requires reconciliation work.
- **Leave uncertain bills unresolved and out of financial balances:** safest when evidence is incomplete; new payments can proceed only for bills whose currency is known, and old-bill balances remain incomplete until reviewed.
- **Assume the likely currency for all bills:** quick, but can misstate customer debts and credits. Not recommended.

**Recommended default for a small dairy:** Reconcile against bills, price lists, and receipts. Backfill only the currency the owner can affirm. Mark uncertain bills as unresolved and do not allocate money to them until corrected.

**Pending owner approval:** State which source records support the interpretation and approve the historical classification approach. This depends directly on real records and blocks applying receipts to legacy bills or presenting a complete historical balance. A limited first release may record receipts for new, confirmed-currency obligations while historical review continues, if the owner accepts that account summaries are incomplete.

### 3. PREPAID customers and account credit

**Existing behavior:** `PREPAID` currently prevents monthly bill generation. The application does not collect advance payments, record credit, calculate prepaid consumption, or restrict service based on a balance. DailyRecords may still record actual collection.

**Plain-language question:** In the farm's day-to-day process, what does "prepaid" mean? Is money paid before collection, and what event uses up that money?

**Options and consequences:**
- **Allow receipts and unallocated account credit for both PREPAID and MONTHLY customers (recommended baseline):** accurately records money received before an eligible bill exists. It does not by itself calculate daily prepaid consumption or change service eligibility.
- **Allow advance credit only for MONTHLY customers:** simpler if PREPAID customers are settled outside this customer ledger, but does not record their cash or remaining prepaid value in Milk Web.
- **Build PREPAID as a consumption wallet:** deducts value against each recorded collection; requires rules for when quantities become chargeable, how prices are selected, negative credit, and corrections. This is a larger domain than the current monthly bill model.
- **Do not accept advance receipts:** simpler but fails to represent money the farm already accepts before a bill is issued.

**Recommended default for a small dairy:** Allow an OWNER to record actual receipts and unallocated credit for any customer, but do not infer that PREPAID means credit exists, automatically deduct daily consumption, or block collection when credit is low. Treat a complete prepaid wallet as a separate future decision.

**Pending owner approval:** Explain the real PREPAID workflow, decide whether the account ledger should record its receipts, and confirm whether any service eligibility depends on credit. This depends on actual operating practice and blocks finalizing how receipts relate to PREPAID; a daily-consumption wallet can safely be postponed.

### 4. Automatic versus explicit payment allocation

**Existing behavior:** There are no payments or allocations. Issued bills are independent records and do not show amounts paid.

**Plain-language question:** When a customer pays, should the owner choose which bill the money covers, or should the system choose for them?

**Options and consequences:**
- **Owner explicitly selects one or more bills (recommended default):** clear to review and explain; leaves any remainder as unallocated credit. Requires the owner to make a selection.
- **System automatically applies to the oldest issued bill(s):** faster for repeat collections, but can apply money contrary to a customer's instruction or farm practice. Must define date ordering, tie-breaking, and treatment of disputed bills.
- **Suggest oldest-first but require owner confirmation:** reduces clicks while preserving control; requires a visible preview and confirmation.
- **Treat each receipt as tied to exactly one bill:** simplest workflow, but prevents one payment covering several bills without splitting the receipt and complicates advance credit.

**Recommended default for a small dairy:** Explicit owner-selected allocation. A convenient "pay this bill" action may preselect that bill, but the owner confirms the amount and target. Do not silently allocate to other bills.

**Pending owner approval:** Confirm how receipts are currently assigned and whether one collection commonly covers multiple months. The exact initial choice is needed before implementing the receipt workflow. Oldest-first suggestions can be postponed until usage justifies them.

### 5. Advance and excess payments

**Existing behavior:** The system has no place to record money before bill issuance or the portion exceeding a bill amount. There is no customer credit or refund capability.

**Plain-language question:** If a customer pays before a bill is ready or pays more than is due, what does the farm promise to do with the extra money?

**Options and consequences:**
- **Keep the excess as unallocated customer credit (recommended default):** records the full receipt and makes unused cash visible; the owner later chooses whether to apply it to a bill. This is not a refund or proof of a particular bill being paid.
- **Automatically carry credit to the next bill:** convenient if this matches established practice, but requires a clear ordering rule and must be visible to the owner/customer.
- **Reject or return excess:** avoids credit carry-forward but may not match cash already received; an actual returned payment must be recorded separately.
- **Do not accept advance funds:** avoids advance credit, but requires the farm to change collection practice if it currently takes deposits/prepayments.

**Recommended default for a small dairy:** Record the entire confirmed receipt; do not over-allocate a bill. Keep the remainder as customer-level unallocated credit and allocate it explicitly later. Do not automatically carry it forward or promise a refund without an owner action.

**Pending owner approval:** Confirm whether advance/excess amounts are held, returned, or applied to later bills, and whether credit carries across billing periods. This depends on customer agreements and cashbook practice and is required for a truthful account summary. Automatic carry-forward and customer self-service can be deferred.

### 6. Incorrect receipts and corrections

**Existing behavior:** Receipts do not exist. The bill domain has no edit or void operation after issue. The proposed model keeps financial history attributable and avoids deleting posted records.

**Plain-language question:** If an owner enters the wrong amount, date, customer, or reference, how should the record be corrected without hiding what happened?

**Options and consequences:**
- **Append a correction/reversal with a required reason, then enter the correct receipt if money was received (recommended default):** preserves the original entry and a clear audit trail; requires an additional correction step.
- **Allow editing before a receipt has been allocated:** easier for simple typos but still changes history and creates race conditions; not recommended for posted financial records.
- **Allow hard deletion of mistaken receipts:** hides the audit trail and makes reconciliation difficult; not recommended.
- **Require a second owner's approval for every correction:** stronger oversight but may block a small farm when only one owner/operator is available.

**Recommended default for a small dairy:** Once posted, never edit or delete a receipt. An OWNER with correction permission records a linked compensating event with a reason; a replacement receipt is added only if money was actually received. Start without mandatory second-person approval, but retain actor and timestamps.

**Pending owner approval:** Confirm who may correct a receipt, required reason details, and whether another person must approve high-value or all corrections. Distinguish an incorrect data entry from a true return of money. A configurable approval threshold can safely be postponed if an OWNER-only process and audit trail are acceptable for the first release.

### 7. Actual refunds

**Existing behavior:** There is no refund process or payment ledger. The proposed first design treats refunds as out of scope and does not currently subtract returned cash from its unallocated-credit formula.

**Plain-language question:** Does the farm ever return money to a customer after receiving it? If so, how is the return documented today?

**Options and consequences:**
- **No refunds through Milk Web in the first release (recommended if refunds are genuinely rare):** simplest; only permit this if an outside cashbook process records refunds and the customer balance is clearly marked incomplete until reconciled.
- **Record a manual refund event linked to the original receipt, without online payment processing (recommended if refunds occur):** captures money leaving the farm and preserves an audit trail; requires owner attribution, reason, date, amount, and validation that cumulative refunds do not exceed the receipt/unreturned amount.
- **Support online refund execution:** integrates with a payment provider and introduces settlement, security, and reconciliation scope; not recommended for this phase.
- **Treat refunds as a negative balance adjustment:** mixes cash returned with non-cash corrections and obscures how much money the farm received/returned; not recommended.

**Recommended default for a small dairy:** Do not automate or execute refunds in Milk Web. If the farm does return cash, record a separate OWNER-entered refund event linked to the receipt; otherwise the account ledger cannot claim to show the true remaining credit. Keep online payment/refund processing out of scope.

**Pending owner approval:** Confirm whether refunds happen in practice and whether they must appear in the customer account. This depends on cashbook/bank records. The UI for initiating refunds can be deferred; the ledger must not present net credit as accurate while real refunds are unrecorded.

### 8. Issued-bill corrections and voids

**Existing behavior:** Bills transition DRAFT to ISSUED. Issued totals and line snapshots have no edit, reopen, or void workflow. Customer reads expose only issued bills.

**Plain-language question:** If an issued bill is wrong, should the farm keep it and record a correction, or formally cancel it and issue a replacement?

**Options and consequences:**
- **Keep the original and add a linked correcting debit/credit adjustment (recommended default for ordinary corrections):** preserves what the customer originally saw and the correction reason; the visible account must distinguish the corrected amount from the original invoice.
- **Void the original and issue a linked replacement:** clearest when the entire bill is invalid, but requires a formal void status/reason, customer notice, and rules for any payments already allocated.
- **Edit the issued bill in place:** appears simple but changes the historical document and makes prior payment allocation/audit results ambiguous; not recommended.
- **Never correct issued bills in the system:** only safe if corrections are exceptionally rare and reconciled outside the customer ledger; creates incomplete balances and customer statements.

**Recommended default for a small dairy:** Never edit or delete an issued bill. Use a linked adjustment for a partial correction. If the whole bill must be replaced, keep the original as a traceable voided document and link the replacement; do not implement that path until allocation handling and customer notification are specified.

**Pending owner approval:** Choose the normal correction path, define who authorizes it, and decide how existing allocations move or remain if the bill is voided. This depends on past corrections and is required before bill correction/void features. A v1 payment ledger can safely defer bill correction functionality if the owner agrees that issued bills remain immutable and errors are handled outside the system with an explicit warning.

### 9. Balance adjustment reasons and approvals

**Existing behavior:** No balance adjustment exists. There is no way to record a non-cash correction, discount, or extra charge separately from a bill or receipt.

**Plain-language question:** Which non-cash changes may an owner make to a customer's account, and what explanation should be kept?

**Options and consequences:**
- **Small controlled reason list plus required note and OWNER attribution (recommended default):** examples might include `BILL_CORRECTION`, `GOODWILL_CREDIT`, `DATA_CORRECTION`, and `OTHER`. Keeps reporting usable while allowing explanation; reason meanings must be agreed.
- **Free-text only:** flexible but difficult to audit or summarize consistently.
- **Require approval by a second OWNER for every adjustment:** adds oversight but may be impractical for a family business with one operator.
- **Allow adjustments without reason or actor tracking:** fastest but not auditable and should not be used.

**Recommended default for a small dairy:** OWNER-only, positive amount with explicit debit/credit direction, a required reason category and short explanation, actor and timestamp. No second-person approval in v1 unless the farm has multiple operators and wants it.

**Pending owner approval:** Approve the allowed reason categories, account-level versus bill-level use, who may post, and whether a value threshold needs a second approver. Do not let a bill credit exceed the remaining bill amount; record any separately approved excess as account-level credit. A larger approval workflow and analytics by reason can safely be postponed.

### 10. Supported payment methods

**Existing behavior:** No payment methods or receipts are represented. There is no online payment integration.

**Plain-language question:** Does the farm receive customer money as cash, bank transfer, or both, and what evidence is kept for each?

**Options and consequences:**
- **Cash only:** simplest if it reflects actual operations, but cannot record bank transfers accurately.
- **Bank transfer only:** suitable only if cash is not accepted; requires a reference/evidence field.
- **Cash and bank transfer (recommended default if both are used):** one receipt workflow with a method field and optional bank reference; the OWNER records a bank payment only after confirming it was received. A reference is not itself proof of settlement.
- **Add card, wallet, or online provider methods:** requires provider/settlement reconciliation and should be a separate integration decision.

**Recommended default for a small dairy:** Support cash and bank transfer when both match actual practice; retain a short optional reference for transfers and a note for cash. Do not collect card or bank-account credentials, and do not treat a transfer screenshot or initiated payment as settled funds unless the owner confirms it.

**Pending owner approval:** Confirm the methods actually accepted, the point at which a transfer is considered received, and whether a receipt number or external reference is used. This depends on operational records; adding other payment channels can safely wait.

## Dependencies and safe deferrals

### Decisions that block a trustworthy first implementation

1. **Currency and legacy bills (Decisions 1-2):** Must know the unit of new amounts and whether old bills can participate in balances. Historical interpretation requires actual evidence; uncertain bills must remain unresolved.
2. **Meaning of PREPAID and account credit (Decision 3):** Must know whether the first ledger records receipts for these customers and whether any consumption-based wallet is expected. A full prepaid wallet can be a later milestone.
3. **Allocation, advance, and excess rules (Decisions 4-5):** Must settle whether credit stays unallocated and who selects bill targets before returning account and bill balances.
4. **Correction and refund treatment (Decisions 6-7):** Must distinguish a mistaken record from real money returned. If refunds occur, either capture them in the ledger or explicitly disclose that balances exclude them.
5. **Issued-bill correction policy (Decision 8):** Can be excluded from initial implementation only if the owner accepts immutable issued bills and the operational treatment of any error is documented.
6. **Adjustment approvals and payment methods (Decisions 9-10):** Confirm allowed adjustments, approval expectations, accepted payment methods, and which operators may record money-changing events before building the owner workflow.

### Work that can be postponed with explicit scope limits

- Automatic oldest-first allocation, allocation suggestions, and customer self-service allocation.
- A daily-consumption prepaid wallet, service blocking based on credit, and online payment processing.
- Multi-currency and foreign-exchange conversion.
- Second-person approval workflows, configurable approval limits, and adjustment analytics.
- Automated refund execution. Refund recording cannot be omitted if actual refunds occur and account credit is represented as complete.
- Bill void/reissue UI, if v1 preserves issued bills unchanged and the owner accepts that bill corrections are handled outside the system temporarily.
- Materialized/cached balances until measured query performance requires them.

These are deferrals, not approved permanent policies. Do not present incomplete or unresolved balances as definitive customer debt or credit.

## Owner approval checklist

Mark each item only after the owner has reviewed the relevant farm records and explicitly approved the answer:

- [ ] **Currency:** Confirm the one operating currency (or explain why multiple currencies are necessary).
- [ ] **Historical bills:** Identify evidence for the currency of existing bills; approve which can be classified and how unresolved ones are treated.
- [ ] **PREPAID:** Describe what PREPAID means in current operations; approve whether receipts/account credit are recorded for those customers. Confirm that consumption-based wallet behavior is deferred or specify it.
- [ ] **Allocation:** Approve explicit owner-selected allocation, or describe the required automatic allocation order and exceptions.
- [ ] **Advance/excess:** Approve whether advance/excess receipts are retained as unallocated credit, carried forward, returned, or handled another way.
- [ ] **Receipt corrections:** Approve who can correct a posted receipt, required reason/evidence, and whether a second approval is required.
- [ ] **Refunds:** Confirm whether real refunds occur; approve ledger recording or explicitly accept that balances exclude externally recorded refunds.
- [ ] **Issued-bill corrections:** Approve adjustment versus linked void/replacement, including treatment of payments already allocated and customer notification.
- [ ] **Adjustments:** Approve reason categories, bill/account scope, operator permissions, and any approval threshold.
- [ ] **Methods:** Confirm cash, bank transfer, or both; define when bank money counts as received and what reference is retained.
- [ ] **Deferrals:** Approve the listed first-release deferrals and ensure no screen or report claims a complete balance when an excluded case could affect it.

Until these approvals are recorded, recommendations remain proposals. Implementation should not proceed past the currency/legacy-data, PREPAID/account-credit, allocation, and minimum receipt/correction policy gates.

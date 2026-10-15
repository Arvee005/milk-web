import { useState } from 'react'
import { groupBillLines, type ReceiptLine } from './receiptLines'
import type { BillBalance, BillLedgerEntry } from './ledgerApi'
import { formatRupees } from './money'

export type BillReceiptProps = {
  billId: string
  period: string
  periodStart: string
  periodEnd: string
  status: 'DRAFT' | 'ISSUED'
  issuedAt: string | null
  totalAmount: number
  lines: ReceiptLine[]
  customerName: string | null
  customerPhone: string | null
  customerAddress?: string | null
  /** Payment status from the ledger, when known. Without it the receipt makes no claim about payment. */
  balance?: Pick<BillBalance, 'status' | 'due' | 'entries'> | null
}

const METHOD_LABELS = { CASH: 'Cash', UPI: 'UPI' } as const

function entryLabel(entry: BillLedgerEntry) {
  const on = shortDate(entry.date)
  switch (entry.type) {
    case 'PAYMENT': return `Paid ${on}${entry.method ? ` · ${METHOD_LABELS[entry.method]}` : ''}`
    case 'CREDIT_APPLIED': return `Credit applied ${on}`
    case 'PAYMENT_REVERSED': return `Payment corrected ${on}`
    case 'ADJUSTMENT_CREDIT': return `Credit · ${entry.reason ?? on}`
    case 'ADJUSTMENT_CHARGE': return `Extra charge · ${entry.reason ?? on}`
  }
}

/** Shown as the effect on what is still owed: payments and credits lower it, charges and corrections raise it. */
function entryEffect(entry: BillLedgerEntry) {
  const lowersDue = entry.type === 'PAYMENT' || entry.type === 'CREDIT_APPLIED' || entry.type === 'ADJUSTMENT_CREDIT'
  const value = Math.abs(entry.amount)
  return (lowersDue ? '−' : '+') + formatRupees(value)
}

const IST = 'Asia/Kolkata'

function monthTitle(period: string) {
  return new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric', timeZone: IST })
    .format(new Date(`${period}-01T00:00:00+05:30`))
}

function shortDate(date: string) {
  return new Intl.DateTimeFormat('en-IN', { day: '2-digit', month: 'short', year: 'numeric', timeZone: IST })
    .format(new Date(`${date}T00:00:00+05:30`))
}

function issueDate(instant: string) {
  return new Intl.DateTimeFormat('en-IN', { day: '2-digit', month: 'short', year: 'numeric', timeZone: IST })
    .format(new Date(instant))
}

function quantity(value: number) {
  return new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 }).format(value)
}

function unitOf(description: string) {
  if (/KILOGRAM/i.test(description)) return 'kg'
  return 'L'
}

function productName(description: string) {
  return description.replace(/\s*\((LITRE|KILOGRAM)\)\s*$/i, '')
}

/** A bill drawn as a printed dairy receipt: totals per product and price, with every collection on request. */
export default function BillReceipt(props: BillReceiptProps) {
  const [expanded, setExpanded] = useState(false)
  const groups = groupBillLines(props.lines)
  const title = monthTitle(props.period)

  return (
    <article className="receipt" aria-label={`${title} bill`}>
      <header className="receipt-head">
        <p className="receipt-brand">Radhika Farms</p>
        <p className="receipt-sub">Monthly milk bill · No. {props.billId.slice(0, 8).toUpperCase()}</p>
        <h2>{title}</h2>
      </header>
      <div className="receipt-rule" />
      <dl className="receipt-meta">
        {props.customerName && <div><dt>Customer</dt><dd>{props.customerName}</dd></div>}
        {props.customerPhone && <div><dt>Phone</dt><dd>{props.customerPhone}</dd></div>}
        {props.customerAddress && <div><dt>Address</dt><dd>{props.customerAddress}</dd></div>}
        <div><dt>Period</dt><dd>{shortDate(props.periodStart)} – {shortDate(props.periodEnd)}</dd></div>
      </dl>
      <div className="receipt-rule" />
      <ul className="receipt-groups">
        {groups.map((group) => (
          <li key={`${group.description}-${group.unitPrice}`}>
            <span className="receipt-line"><span>{productName(group.description)}</span><span>{formatRupees(group.amount)}</span></span>
            <span className="receipt-detail">
              {quantity(group.quantity)} {unitOf(group.description)} × {formatRupees(group.unitPrice)} · {group.collections} collection{group.collections === 1 ? '' : 's'}
            </span>
          </li>
        ))}
      </ul>
      <div className="receipt-rule" />
      <p className="receipt-line receipt-total"><span>Bill total</span><span>{formatRupees(props.totalAmount)}</span></p>
      {props.balance && (
        <>
          {props.balance.entries.length > 0 && (
            <ul className="receipt-payments" aria-label="Payments and adjustments">
              {props.balance.entries.map((entry, index) => (
                <li className="receipt-line" key={`${entry.type}-${entry.date}-${index}`}>
                  <span>{entryLabel(entry)}</span><span>{entryEffect(entry)}</span>
                </li>
              ))}
            </ul>
          )}
          <div className="receipt-rule" />
          <p className={`receipt-line receipt-balance ${props.balance.due > 0 ? 'owed' : 'settled'}`}>
            <span>{props.balance.due > 0 ? 'Balance due' : 'Balance'}</span><span>{formatRupees(props.balance.due)}</span>
          </p>
        </>
      )}
      <p className="receipt-stamp-row">
        {props.balance?.status === 'PAID' ? (
          <span className="receipt-stamp issued">Paid in full</span>
        ) : (
          <span className={`receipt-stamp ${props.status !== 'ISSUED' ? 'draft' : props.balance ? 'owed' : 'issued'}`}>
            {props.status === 'ISSUED' && props.issuedAt ? `Issued ${issueDate(props.issuedAt)}` : 'Draft · not issued'}
          </span>
        )}
      </p>
      <button className="bill-details-toggle" type="button" aria-expanded={expanded} onClick={() => setExpanded((value) => !value)}>
        {expanded ? 'Hide bill details' : `View bill details (${props.lines.length} items)`}
      </button>
      {expanded && (
        <div className="receipt-lines">
          <h3>Bill items</h3>
          <div className="receipt-table-wrap">
            <table>
              <thead><tr><th scope="col">Date</th><th scope="col">Slot</th><th scope="col">Item</th><th scope="col">Qty</th><th scope="col">Rate</th><th scope="col">Amount</th></tr></thead>
              <tbody>
                {props.lines.map((line, index) => (
                  <tr key={`${line.recordDate}-${line.slot}-${index}`}>
                    <td>{shortDate(line.recordDate)}</td>
                    <td>{line.slot === 'MORNING' ? 'Morning' : 'Evening'}</td>
                    <td>{line.description}</td>
                    <td>{quantity(line.quantity)}</td>
                    <td>{formatRupees(line.unitPrice)}</td>
                    <td>{formatRupees(line.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
      <p className="receipt-note">
        {props.balance ? 'Amounts are in Indian rupees.' : 'Amounts are in Indian rupees. This bill is not a payment receipt.'}
      </p>
    </article>
  )
}

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { isUnauthenticatedError } from './authApi'
import {
  getOwnerAccount,
  getOwnerTransactions,
  ledgerErrorMessage,
  newIdempotencyKey,
  previewPayment,
  recordAdjustment,
  recordPayment,
  recordRefund,
  reversePayment,
  type Account,
  type PaymentMethod,
  type Transaction,
} from './ledgerApi'
import { formatRupees } from './money'

type Tab = 'pay' | 'adjust' | 'refund' | 'history'

type OwnerBillPaymentsProps = {
  token: string
  customerId: string
  customerName: string
  billId: string
  onChanged: () => void
  onSessionExpired: () => void
  api?: {
    getOwnerAccount: typeof getOwnerAccount
    getOwnerTransactions: typeof getOwnerTransactions
    recordPayment: typeof recordPayment
    recordAdjustment: typeof recordAdjustment
    recordRefund: typeof recordRefund
    reversePayment: typeof reversePayment
  }
}

const defaultApi = { getOwnerAccount, getOwnerTransactions, recordPayment, recordAdjustment, recordRefund, reversePayment }

function todayInIndia() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date())
}

function monthName(period: string) {
  return new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${period}-01T00:00:00Z`))
}

function shortDate(date: string) {
  return new Intl.DateTimeFormat('en-IN', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${date}T00:00:00Z`))
}

const TRANSACTION_LABELS: Record<Transaction['type'], string> = {
  PAYMENT: 'Payment',
  PAYMENT_REVERSAL: 'Payment corrected',
  REFUND: 'Money returned',
  CREDIT: 'Credit',
  CHARGE: 'Extra charge',
}

/** Owner money actions for one customer, shown beside a bill. Every save carries a one-time request key. */
export default function OwnerBillPayments({
  token,
  customerId,
  customerName,
  billId,
  onChanged,
  onSessionExpired,
  api = defaultApi,
}: OwnerBillPaymentsProps) {
  const firstName = customerName.trim().split(/\s+/)[0] || customerName
  const [tab, setTab] = useState<Tab>('pay')
  const [account, setAccount] = useState<Account | null>(null)
  const [history, setHistory] = useState<Transaction[]>([])
  const [loadError, setLoadError] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const keyRef = useRef(newIdempotencyKey())

  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState<PaymentMethod>('CASH')
  const [reference, setReference] = useState('')
  const [receivedOn, setReceivedOn] = useState(todayInIndia())
  const [note, setNote] = useState('')
  const [direction, setDirection] = useState<'CREDIT' | 'DEBIT'>('CREDIT')
  const [adjustAmount, setAdjustAmount] = useState('')
  const [adjustReason, setAdjustReason] = useState('')
  const [refundAmount, setRefundAmount] = useState('')
  const [refundMethod, setRefundMethod] = useState<PaymentMethod>('CASH')
  const [refundReason, setRefundReason] = useState('')
  const [correcting, setCorrecting] = useState<string | null>(null)
  const [correctionReason, setCorrectionReason] = useState('')

  const handle = useCallback((cause: unknown) => {
    if (isUnauthenticatedError(cause)) onSessionExpired()
    else setError(ledgerErrorMessage(cause))
  }, [onSessionExpired])

  const load = useCallback(async () => {
    setLoadError('')
    try {
      const [nextAccount, nextHistory] = await Promise.all([
        api.getOwnerAccount(token, customerId),
        api.getOwnerTransactions(token, customerId),
      ])
      setAccount(nextAccount)
      setHistory(nextHistory)
      setAmount((current) => current || (nextAccount.amountDue > 0 ? nextAccount.amountDue.toFixed(2) : ''))
      setRefundAmount(nextAccount.credit > 0 ? nextAccount.credit.toFixed(2) : '')
    } catch (cause) {
      if (isUnauthenticatedError(cause)) onSessionExpired()
      else setLoadError(ledgerErrorMessage(cause))
    }
  }, [api, customerId, onSessionExpired, token])

  useEffect(() => { void load() }, [load])

  function switchTab(next: Tab) {
    setTab(next)
    setError('')
    setNotice('')
  }

  // A successful save gets a new key so the next entry is not mistaken for a repeat; failures keep the key,
  // so pressing Save again after a network error cannot record the same money twice.
  async function save(action: () => Promise<string>) {
    if (saving) return
    setSaving(true)
    setError('')
    setNotice('')
    try {
      const message = await action()
      keyRef.current = newIdempotencyKey()
      setNotice(message)
      await load()
      onChanged()
    } catch (cause) {
      handle(cause)
    } finally {
      setSaving(false)
    }
  }

  function submitPayment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void save(async () => {
      const result = await api.recordPayment(token, customerId,
        { amount: Number(amount), method, reference, note, receivedOn }, keyRef.current)
      const cleared = result.appliedTo.map((part) => monthName(part.period).split(' ')[0]).join(', ')
      setAmount('')
      setReference('')
      setNote('')
      return `Saved ${formatRupees(result.amount)} from ${firstName}.`
        + (cleared ? ` Applied to ${cleared}.` : '')
        + (result.keptAsCredit > 0 ? ` ${formatRupees(result.keptAsCredit)} kept as credit.` : '')
    })
  }

  function submitAdjustment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void save(async () => {
      await api.recordAdjustment(token, customerId,
        { direction, amount: Number(adjustAmount), reason: adjustReason, billId }, keyRef.current)
      setAdjustAmount('')
      setAdjustReason('')
      return direction === 'CREDIT' ? 'Credit saved.' : 'Extra charge saved.'
    })
  }

  function submitRefund(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void save(async () => {
      await api.recordRefund(token, customerId,
        { amount: Number(refundAmount), method: refundMethod, reason: refundReason }, keyRef.current)
      setRefundReason('')
      return `Recorded ${formatRupees(Number(refundAmount))} returned to ${firstName}.`
    })
  }

  function submitCorrection(paymentId: string) {
    void save(async () => {
      await api.reversePayment(token, paymentId, correctionReason)
      setCorrecting(null)
      setCorrectionReason('')
      return 'Payment corrected. Record the right payment if money was received.'
    })
  }

  const amountNumber = Number(amount)
  const preview = account && amountNumber > 0 ? previewPayment(account.bills, amountNumber) : null

  if (loadError) {
    return (
      <section className="money-panel" aria-label={`Payments for ${customerName}`}>
        <p className="alert error-alert" role="alert">{loadError}</p>
        <button className="secondary-button" onClick={() => void load()}>Try again</button>
      </section>
    )
  }

  return (
    <section className="money-panel" aria-label={`Payments for ${customerName}`}>
      <div className="money-summary">
        <h3>{customerName}</h3>
        {account ? (
          <p>
            {account.amountDue > 0
              ? <>Owes <strong className="money-owed">{formatRupees(account.amountDue)}</strong> across all bills.</>
              : <>Nothing owed.</>}
            {account.credit > 0 && <> Credit <strong className="money-credit">{formatRupees(account.credit)}</strong>.</>}
          </p>
        ) : <p className="muted">Loading account…</p>}
      </div>

      <div className="money-tabs" role="tablist" aria-label="Money actions">
        {([['pay', 'Record payment'], ['adjust', 'Adjust this bill'], ['refund', 'Return credit'], ['history', 'History']] as const)
          .filter(([id]) => id !== 'refund' || (account?.credit ?? 0) > 0)
          .map(([id, label]) => (
            <button key={id} type="button" role="tab" aria-selected={tab === id} onClick={() => switchTab(id)}>{label}</button>
          ))}
      </div>

      {error && <p className="alert error-alert" role="alert">{error}</p>}
      {notice && <p className="alert success-alert" role="status">{notice}</p>}

      {tab === 'pay' && (
        <form className="money-form" aria-label="Record a payment" onSubmit={submitPayment}>
          <div className="money-row">
            <label htmlFor="pay-amount">Amount (₹)
              <input id="pay-amount" inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} required />
            </label>
            <label htmlFor="pay-method">Method
              <select id="pay-method" value={method} onChange={(event) => setMethod(event.target.value as PaymentMethod)}>
                <option value="CASH">Cash</option><option value="UPI">UPI</option>
              </select>
            </label>
          </div>
          {method === 'UPI' && (
            <label htmlFor="pay-reference">UPI reference (optional)
              <input id="pay-reference" maxLength={64} value={reference} onChange={(event) => setReference(event.target.value)} />
            </label>
          )}
          <div className="money-row">
            <label htmlFor="pay-date">Received on
              <input id="pay-date" type="date" max={todayInIndia()} value={receivedOn} onChange={(event) => setReceivedOn(event.target.value)} required />
            </label>
            <label htmlFor="pay-note">Note for you (optional)
              <input id="pay-note" maxLength={255} value={note} onChange={(event) => setNote(event.target.value)} />
            </label>
          </div>
          <div className="money-preview" aria-live="polite">
            {!preview ? <span className="muted">Enter an amount to see which bills it clears.</span> : (
              <>
                <strong>This payment will:</strong>
                {preview.parts.map((part) => (
                  <span className="receipt-line" key={part.billId}>
                    <span>{part.clears ? 'Clear' : 'Pay towards'} {monthName(part.period)}</span><span>{formatRupees(part.amount)}</span>
                  </span>
                ))}
                {preview.credit > 0 && (
                  <span className="receipt-line money-credit"><span>Keep as credit for the next bill</span><span>{formatRupees(preview.credit)}</span></span>
                )}
              </>
            )}
          </div>
          <p className="form-hint">For UPI, record it only once the money shows in the farm’s account.</p>
          <button className="primary-button" disabled={saving}>{saving ? 'Saving…' : 'Save payment'}</button>
        </form>
      )}

      {tab === 'adjust' && (
        <form className="money-form" aria-label="Adjust this bill" onSubmit={submitAdjustment}>
          <div className="money-row">
            <label htmlFor="adjust-direction">Type
              <select id="adjust-direction" value={direction} onChange={(event) => setDirection(event.target.value as 'CREDIT' | 'DEBIT')}>
                <option value="CREDIT">Credit (lowers the bill)</option>
                <option value="DEBIT">Extra charge</option>
              </select>
            </label>
            <label htmlFor="adjust-amount">Amount (₹)
              <input id="adjust-amount" inputMode="decimal" value={adjustAmount} onChange={(event) => setAdjustAmount(event.target.value)} required />
            </label>
          </div>
          <label htmlFor="adjust-reason">Reason (the customer sees this)
            <input id="adjust-reason" maxLength={255} placeholder="₹30 off, milk was sour" value={adjustReason}
              onChange={(event) => setAdjustReason(event.target.value)} required />
          </label>
          <p className="form-hint">A credit larger than what is owed on this bill is kept as credit for the next bill.</p>
          <button className="primary-button" disabled={saving}>{saving ? 'Saving…' : 'Save adjustment'}</button>
        </form>
      )}

      {tab === 'refund' && (
        <form className="money-form" aria-label="Return credit" onSubmit={submitRefund}>
          <div className="money-row">
            <label htmlFor="refund-amount">Money returned (₹)
              <input id="refund-amount" inputMode="decimal" value={refundAmount} onChange={(event) => setRefundAmount(event.target.value)} required />
            </label>
            <label htmlFor="refund-method">Method
              <select id="refund-method" value={refundMethod} onChange={(event) => setRefundMethod(event.target.value as PaymentMethod)}>
                <option value="CASH">Cash</option><option value="UPI">UPI</option>
              </select>
            </label>
          </div>
          <label htmlFor="refund-reason">Reason
            <input id="refund-reason" maxLength={255} value={refundReason} onChange={(event) => setRefundReason(event.target.value)} required />
          </label>
          <p className="form-hint">Milk Web only records this; it does not send money.</p>
          <button className="secondary-button" disabled={saving}>{saving ? 'Saving…' : 'Record money returned'}</button>
        </form>
      )}

      {tab === 'history' && (
        history.length === 0 ? <p className="muted">No payments or adjustments yet.</p> : (
          <ul className="money-history">
            {history.map((entry) => (
              <li key={entry.id}>
                <div className="receipt-line">
                  <span>
                    <strong>{TRANSACTION_LABELS[entry.type]}</strong> · {shortDate(entry.date)}
                    {entry.method ? ` · ${entry.method === 'UPI' ? 'UPI' : 'Cash'}` : ''}
                    {entry.billPeriod ? ` · ${monthName(entry.billPeriod)}` : ''}
                  </span>
                  <span className={entry.reversed ? 'money-reversed' : undefined}>{formatRupees(Math.abs(entry.amount))}</span>
                </div>
                {(entry.reason || entry.reference || entry.note) && (
                  <p className="muted">{[entry.reason, entry.reference && `Ref ${entry.reference}`, entry.note].filter(Boolean).join(' · ')}</p>
                )}
                {entry.type === 'PAYMENT' && !entry.reversed && (correcting === entry.id ? (
                  <div className="money-correct">
                    <label htmlFor={`correct-${entry.id}`}>Why is this payment wrong?
                      <input id={`correct-${entry.id}`} maxLength={255} value={correctionReason}
                        onChange={(event) => setCorrectionReason(event.target.value)} />
                    </label>
                    <div className="money-correct-actions">
                      <button type="button" className="secondary-button" disabled={saving} onClick={() => submitCorrection(entry.id)}>Correct payment</button>
                      <button type="button" className="text-button" onClick={() => { setCorrecting(null); setCorrectionReason('') }}>Cancel</button>
                    </div>
                  </div>
                ) : (
                  <button type="button" className="text-button" onClick={() => { setCorrecting(entry.id); setCorrectionReason(''); setError('') }}>
                    Correct this payment
                  </button>
                ))}
              </li>
            ))}
          </ul>
        )
      )}
    </section>
  )
}

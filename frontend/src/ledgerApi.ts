import { apiRequest } from './authApi.ts'

export type PaymentMethod = 'CASH' | 'UPI'
export type BillPaymentStatus = 'PAID' | 'PART_PAID' | 'UNPAID'

export type AccountTotals = { amountDue: number; credit: number }

export type BillLedgerEntry = {
  type: 'PAYMENT' | 'PAYMENT_REVERSED' | 'CREDIT_APPLIED' | 'ADJUSTMENT_CREDIT' | 'ADJUSTMENT_CHARGE'
  date: string
  method: PaymentMethod | null
  amount: number
  reason: string | null
}

export type BillBalance = {
  billId: string
  customerId: string
  customerName: string
  period: string
  periodStart: string
  issuedAt: string | null
  billTotal: number
  charges: number
  credits: number
  amountToPay: number
  paid: number
  due: number
  status: BillPaymentStatus
  entries: BillLedgerEntry[]
}

export type Account = {
  customerId: string
  customerName: string
  currency: string
  amountDue: number
  credit: number
  totalBilled: number
  totalReceived: number
  bills: BillBalance[]
}

export type AccountSummary = { customerId: string; name: string; phone: string | null; amountDue: number; credit: number }

export type Transaction = {
  id: string
  type: 'PAYMENT' | 'PAYMENT_REVERSAL' | 'REFUND' | 'CREDIT' | 'CHARGE'
  date: string
  recordedAt: string
  amount: number
  method: PaymentMethod | null
  reference: string | null
  reason: string | null
  note: string | null
  billId: string | null
  billPeriod: string | null
  paymentId: string | null
  reversed: boolean
}

export type AppliedAmount = { billId: string; period: string; amount: number }

export type PaymentResult = {
  id: string
  amount: number
  appliedTo: AppliedAmount[]
  keptAsCredit: number
  account: AccountTotals
}

type Options = Parameters<typeof apiRequest>[3]

const STATUS_LABELS: Record<BillPaymentStatus, string> = { PAID: 'Paid', PART_PAID: 'Part paid', UNPAID: 'Unpaid' }

export function billPaymentStatusLabel(status: BillPaymentStatus) {
  return STATUS_LABELS[status]
}

/** A fresh key per form submission; retries of the same submission reuse it so nothing is recorded twice. */
export function newIdempotencyKey() {
  return globalThis.crypto.randomUUID()
}

export type PaymentPreview = { parts: { billId: string; period: string; amount: number; clears: boolean }[]; credit: number }

/**
 * What a payment will do, using the same rule as the server: oldest unpaid bill first, the rest kept as credit.
 * Works in paise so decimal amounts never drift.
 */
export function previewPayment(bills: Pick<BillBalance, 'billId' | 'period' | 'periodStart' | 'due'>[], amount: number): PaymentPreview {
  let left = Math.round(amount * 100)
  const parts: PaymentPreview['parts'] = []
  const oldestFirst = [...bills].sort((a, b) => a.periodStart.localeCompare(b.periodStart))
  for (const bill of oldestFirst) {
    const due = Math.round(bill.due * 100)
    if (left <= 0) break
    if (due <= 0) continue
    const take = Math.min(due, left)
    parts.push({ billId: bill.billId, period: bill.period, amount: take / 100, clears: take === due })
    left -= take
  }
  return { parts, credit: Math.max(0, left) / 100 }
}

export function ledgerErrorMessage(error: unknown) {
  if (error instanceof TypeError) return 'Unable to connect to Radhika Farms. Check your connection and try again.'
  if (error instanceof Error) return error.message
  return 'Something went wrong. Try again.'
}

// ---------------------------------------------------------------- response checks

const isNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)
const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null

function isBillBalance(value: unknown): value is BillBalance {
  return isObject(value) && typeof value.billId === 'string' && typeof value.period === 'string'
    && typeof value.periodStart === 'string' && isNumber(value.amountToPay) && isNumber(value.paid)
    && isNumber(value.due) && (value.status === 'PAID' || value.status === 'PART_PAID' || value.status === 'UNPAID')
    && Array.isArray(value.entries)
}

function isAccount(value: unknown): value is Account {
  return isObject(value) && typeof value.customerId === 'string' && isNumber(value.amountDue)
    && isNumber(value.credit) && Array.isArray(value.bills) && value.bills.every(isBillBalance)
}

function isTransaction(value: unknown): value is Transaction {
  return isObject(value) && typeof value.id === 'string' && typeof value.type === 'string'
    && typeof value.date === 'string' && isNumber(value.amount) && typeof value.reversed === 'boolean'
}

function checked<T>(value: unknown, guard: (value: unknown) => value is T, what: string): T {
  if (!guard(value)) throw new Error(`The server returned invalid ${what}.`)
  return value
}

// ---------------------------------------------------------------- customer

export async function getCustomerAccount(token: string, options: Options = {}): Promise<Account> {
  const body = await apiRequest<unknown>('/customer/account', token, undefined, { ...options, fallback: 'Could not load your account.' })
  return checked(body, isAccount, 'account details')
}

export async function getCustomerTransactions(token: string, options: Options = {}): Promise<Transaction[]> {
  const body = await apiRequest<unknown>('/customer/transactions?limit=100', token, undefined, { ...options, fallback: 'Could not load your payments.' })
  if (!Array.isArray(body) || !body.every(isTransaction)) throw new Error('The server returned invalid payment history.')
  return body
}

// ---------------------------------------------------------------- owner reads

export async function getOwnerAccount(token: string, customerId: string, options: Options = {}): Promise<Account> {
  const body = await apiRequest<unknown>(`/owner/customers/${customerId}/account`, token, undefined, { ...options, fallback: 'Could not load the account.' })
  return checked(body, isAccount, 'account details')
}

export async function getOwnerTransactions(token: string, customerId: string, options: Options = {}): Promise<Transaction[]> {
  const body = await apiRequest<unknown>(`/owner/customers/${customerId}/transactions?limit=200`, token, undefined, { ...options, fallback: 'Could not load the history.' })
  if (!Array.isArray(body) || !body.every(isTransaction)) throw new Error('The server returned invalid payment history.')
  return body
}

export async function getPeriodBalances(token: string, period: string, options: Options = {}): Promise<BillBalance[]> {
  const body = await apiRequest<unknown>(`/owner/billing-periods/${period}/balances`, token, undefined, { ...options, fallback: 'Could not load payment status.' })
  if (!Array.isArray(body) || !body.every(isBillBalance)) throw new Error('The server returned invalid payment status.')
  return body
}

export async function getAccountsWithBalances(token: string, options: Options = {}): Promise<AccountSummary[]> {
  const body = await apiRequest<unknown>('/owner/accounts', token, undefined, { ...options, fallback: 'Could not load balances.' })
  if (!Array.isArray(body)) throw new Error('The server returned invalid balances.')
  return body as AccountSummary[]
}

// ---------------------------------------------------------------- owner writes

function send(path: string, token: string, body: unknown, key: string | null, fallback: string, options: Options) {
  return apiRequest<unknown>(path, token, {
    method: 'POST',
    headers: key ? { 'Idempotency-Key': key } : undefined,
    body: JSON.stringify(body),
  }, { ...options, fallback })
}

function positiveAmount(amount: number) {
  if (!Number.isFinite(amount) || amount <= 0) throw new Error('Enter an amount above zero.')
  if (Math.abs(amount * 100 - Math.round(amount * 100)) > 1e-6) throw new Error('Amounts can have at most 2 decimal places.')
}

export async function recordPayment(
  token: string,
  customerId: string,
  payment: { amount: number; method: PaymentMethod; reference?: string; note?: string; receivedOn?: string },
  key: string,
  options: Options = {},
): Promise<PaymentResult> {
  positiveAmount(payment.amount)
  const body = await send(`/owner/customers/${customerId}/payments`, token, {
    amount: payment.amount.toFixed(2),
    method: payment.method,
    reference: payment.method === 'UPI' && payment.reference?.trim() ? payment.reference.trim() : null,
    note: payment.note?.trim() || null,
    receivedOn: payment.receivedOn || null,
  }, key, 'Could not save the payment.', options)
  if (!isObject(body) || typeof body.id !== 'string' || !Array.isArray(body.appliedTo)) {
    throw new Error('The server returned an invalid payment.')
  }
  return body as PaymentResult
}

export async function reversePayment(token: string, paymentId: string, reason: string, options: Options = {}) {
  if (!reason.trim()) throw new Error('Give a reason for the correction.')
  return send(`/owner/payments/${paymentId}/reversal`, token, { reason: reason.trim() }, null,
    'Could not correct the payment.', options)
}

export async function recordAdjustment(
  token: string,
  customerId: string,
  adjustment: { direction: 'CREDIT' | 'DEBIT'; amount: number; reason: string; billId: string | null },
  key: string,
  options: Options = {},
) {
  positiveAmount(adjustment.amount)
  if (!adjustment.reason.trim()) throw new Error('Give a reason.')
  return send(`/owner/customers/${customerId}/adjustments`, token, {
    direction: adjustment.direction,
    amount: adjustment.amount.toFixed(2),
    reason: adjustment.reason.trim(),
    billId: adjustment.billId,
  }, key, 'Could not save the adjustment.', options)
}

export async function recordRefund(
  token: string,
  customerId: string,
  refund: { amount: number; method: PaymentMethod; reason: string; refundedOn?: string },
  key: string,
  options: Options = {},
) {
  positiveAmount(refund.amount)
  if (!refund.reason.trim()) throw new Error('Give a reason.')
  return send(`/owner/customers/${customerId}/refunds`, token, {
    amount: refund.amount.toFixed(2),
    method: refund.method,
    reason: refund.reason.trim(),
    refundedOn: refund.refundedOn || null,
  }, key, 'Could not record the money returned.', options)
}

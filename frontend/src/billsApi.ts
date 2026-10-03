import { apiRequest } from './authApi.ts'

export type CustomerBillLine = {
  recordDate: string
  slot: 'MORNING' | 'EVENING'
  description: string
  quantity: number
  unitPrice: number
  amount: number
}

export type CustomerBill = {
  id: string
  period: string
  periodStart: string
  periodEnd: string
  totalAmount: number
  status: 'ISSUED'
  issuedAt: string
  lines: CustomerBillLine[]
}

export type CustomerBillPage = {
  content: CustomerBill[]
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export type BillsViewState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'empty' }
  | { status: 'ready'; page: CustomerBillPage }

export const CUSTOMER_BILL_PAGE_SIZE = 10

export function billStatusLabel(status: CustomerBill['status']) {
  return status === 'ISSUED' ? 'Issued' : 'Unknown'
}

export function billsViewState(page: CustomerBillPage | null, loading: boolean, error: string): BillsViewState {
  if (loading) return { status: 'loading' }
  if (error) return { status: 'error', message: error }
  if (page === null || page.content.length === 0) return { status: 'empty' }
  return { status: 'ready', page }
}

export function billErrorMessage(error: unknown) {
  if (error instanceof TypeError) {
    return 'Unable to connect to Milk Web. Check your connection and try again.'
  }
  if (error instanceof Error) return error.message
  return 'Could not load your bills.'
}

export async function getCustomerBills(
  token: string,
  page: number,
  size = CUSTOMER_BILL_PAGE_SIZE,
  options: Parameters<typeof apiRequest>[3] = {},
): Promise<CustomerBillPage> {
  if (!Number.isInteger(page) || page < 0) throw new Error('Page must be zero or greater.')
  if (!Number.isInteger(size) || size < 1 || size > 100) {
    throw new Error('Page size must be between 1 and 100.')
  }
  const body = await apiRequest<unknown>(
    `/customer/bills?page=${page}&size=${size}`,
    token,
    undefined,
    { ...options, fallback: 'Could not load your bills.' },
  )
  if (!isCustomerBillPage(body)) throw new Error('The server returned invalid bill details.')
  return body
}

function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function isCustomerBillPage(value: unknown): value is CustomerBillPage {
  if (typeof value !== 'object' || value === null || !('content' in value) || !Array.isArray(value.content)) {
    return false
  }
  return value.content.every(isCustomerBill)
    && 'number' in value && typeof value.number === 'number'
    && 'size' in value && typeof value.size === 'number'
    && 'totalElements' in value && typeof value.totalElements === 'number'
    && 'totalPages' in value && typeof value.totalPages === 'number'
}

function isCustomerBill(value: unknown): value is CustomerBill {
  if (typeof value !== 'object' || value === null) return false
  return 'id' in value && typeof value.id === 'string'
    && 'period' in value && typeof value.period === 'string' && /^\d{4}-(0[1-9]|1[0-2])$/.test(value.period)
    && 'periodStart' in value && typeof value.periodStart === 'string' && validDate(value.periodStart)
    && 'periodEnd' in value && typeof value.periodEnd === 'string' && validDate(value.periodEnd)
    && 'totalAmount' in value && typeof value.totalAmount === 'number'
    && 'status' in value && value.status === 'ISSUED'
    && 'issuedAt' in value && typeof value.issuedAt === 'string' && !Number.isNaN(Date.parse(value.issuedAt))
    && 'lines' in value && Array.isArray(value.lines) && value.lines.every(isCustomerBillLine)
}

function isCustomerBillLine(value: unknown): value is CustomerBillLine {
  if (typeof value !== 'object' || value === null) return false
  return 'recordDate' in value && typeof value.recordDate === 'string' && validDate(value.recordDate)
    && 'slot' in value && (value.slot === 'MORNING' || value.slot === 'EVENING')
    && 'description' in value && typeof value.description === 'string'
    && 'quantity' in value && typeof value.quantity === 'number'
    && 'unitPrice' in value && typeof value.unitPrice === 'number'
    && 'amount' in value && typeof value.amount === 'number'
}

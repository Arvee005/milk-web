import { apiRequest } from './authApi.ts'

export type DailyRecordSlot = 'MORNING' | 'EVENING'
export type DailyRecordStatus = 'COLLECTED' | 'EXTRA' | 'SKIPPED' | 'PAUSED'

export type CustomerDailyRecord = {
  id: string
  recordDate: string
  slot: DailyRecordSlot
  productName: string
  productUnit: 'LITRE' | 'KILOGRAM'
  quantity: number
  unitPriceSnapshot: number | null
  amount: number
  status: DailyRecordStatus
}

export type DailyRecordPage = {
  content: CustomerDailyRecord[]
  totalPages: number
  totalElements: number
  number: number
  size: number
}

export type DailyRecordFilters = {
  recordDate?: string
  fromDate?: string
  toDate?: string
  slot?: DailyRecordSlot
  status?: DailyRecordStatus
  page?: number
  size?: number
}

export type DailyCollectionSlot = {
  slot: DailyRecordSlot
  records: CustomerDailyRecord[]
}

export type RecordsViewState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'empty' }
  | { status: 'ready'; records: CustomerDailyRecord[] }

export const DAILY_RECORD_PAGE_SIZE = 20
export const MAX_DAILY_RECORD_RANGE_DAYS = 366

const STATUS_LABELS: Record<DailyRecordStatus, string> = {
  COLLECTED: 'Collected',
  EXTRA: 'Extra milk',
  SKIPPED: 'Skipped',
  PAUSED: 'Paused',
}

export function dailyRecordStatusLabel(status: DailyRecordStatus) {
  return STATUS_LABELS[status]
}

export function dailyCollectionSlots(records: CustomerDailyRecord[]): DailyCollectionSlot[] {
  return (['MORNING', 'EVENING'] as const).map((slot) => ({
    slot,
    records: records.filter((record) => record.slot === slot),
  }))
}

export function recordsViewState(
  records: CustomerDailyRecord[],
  loading: boolean,
  error: string,
): RecordsViewState {
  if (loading) return { status: 'loading' }
  if (error) return { status: 'error', message: error }
  return records.length > 0 ? { status: 'ready', records } : { status: 'empty' }
}

function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

export function validateDailyRecordFilters(filters: DailyRecordFilters) {
  if (filters.recordDate && (filters.fromDate || filters.toDate)) {
    return 'Choose a specific date or a date range, not both.'
  }
  if ((filters.fromDate && !filters.toDate) || (!filters.fromDate && filters.toDate)) {
    return 'Choose both a start and end date.'
  }
  if (filters.recordDate && !validDate(filters.recordDate)) return 'Choose a valid date.'
  if (filters.fromDate && filters.toDate) {
    if (!validDate(filters.fromDate) || !validDate(filters.toDate)) return 'Choose valid start and end dates.'
    const days = (Date.parse(`${filters.toDate}T00:00:00Z`) - Date.parse(`${filters.fromDate}T00:00:00Z`))
      / 86_400_000
    if (days < 0) return 'The start date must be on or before the end date.'
    if (days >= MAX_DAILY_RECORD_RANGE_DAYS) return 'Date ranges cannot exceed 366 calendar days.'
  }
  if (filters.page !== undefined && (!Number.isInteger(filters.page) || filters.page < 0)) {
    return 'Page must be zero or greater.'
  }
  if (filters.size !== undefined
    && (!Number.isInteger(filters.size) || filters.size < 1 || filters.size > 100)) {
    return 'Page size must be between 1 and 100.'
  }
  return ''
}

export function dailyRecordErrorMessage(error: unknown) {
  if (error instanceof TypeError) {
    return 'Unable to connect to Milk Web. Check your connection and try again.'
  }
  if (error instanceof Error) return error.message
  return 'Could not load collection records.'
}

export async function getCustomerDailyRecords(
  token: string,
  filters: DailyRecordFilters,
  options: Parameters<typeof apiRequest>[3] = {},
): Promise<DailyRecordPage> {
  const validationError = validateDailyRecordFilters(filters)
  if (validationError) throw new Error(validationError)

  const params = new URLSearchParams()
  for (const key of ['recordDate', 'fromDate', 'toDate', 'slot', 'status', 'page', 'size'] as const) {
    const value = filters[key]
    if (value !== undefined && value !== '') params.set(key, String(value))
  }
  const query = params.size > 0 ? `?${params.toString()}` : ''
  const body = await apiRequest<unknown>(
    `/customer/daily-records${query}`,
    token,
    undefined,
    { ...options, fallback: 'Could not load collection records.' },
  )
  if (!isDailyRecordPage(body)) throw new Error('The server returned invalid collection records.')
  return body
}

function isDailyRecordPage(value: unknown): value is DailyRecordPage {
  if (typeof value !== 'object' || value === null || !('content' in value) || !Array.isArray(value.content)) {
    return false
  }
  return value.content.every(isDailyRecord)
    && 'totalPages' in value && typeof value.totalPages === 'number'
    && 'totalElements' in value && typeof value.totalElements === 'number'
    && 'number' in value && typeof value.number === 'number'
    && 'size' in value && typeof value.size === 'number'
}

function isDailyRecord(value: unknown): value is CustomerDailyRecord {
  if (typeof value !== 'object' || value === null) return false
  return 'id' in value && typeof value.id === 'string'
    && 'recordDate' in value && typeof value.recordDate === 'string' && validDate(value.recordDate)
    && 'slot' in value && (value.slot === 'MORNING' || value.slot === 'EVENING')
    && 'productName' in value && typeof value.productName === 'string'
    && 'productUnit' in value && (value.productUnit === 'LITRE' || value.productUnit === 'KILOGRAM')
    && 'quantity' in value && typeof value.quantity === 'number'
    && 'unitPriceSnapshot' in value && (typeof value.unitPriceSnapshot === 'number' || value.unitPriceSnapshot === null)
    && 'amount' in value && typeof value.amount === 'number'
    && 'status' in value && (value.status === 'COLLECTED' || value.status === 'EXTRA'
      || value.status === 'SKIPPED' || value.status === 'PAUSED')
}

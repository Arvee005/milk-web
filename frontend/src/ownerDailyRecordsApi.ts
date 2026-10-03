import { apiRequest } from './authApi.ts'

export type OwnerDailyRecordStatus = 'COLLECTED' | 'EXTRA' | 'SKIPPED' | 'PAUSED'
export type OwnerDailyRecordSlot = 'MORNING' | 'EVENING'

export type OwnerCustomerOption = {
  id: string
  name: string
  phone: string | null
  status: 'ACTIVE' | 'PAUSED' | 'INACTIVE'
}

export type OwnerProductOption = {
  id: string
  name: string
  unit: 'LITRE' | 'KILOGRAM'
  price: number
  status: 'ACTIVE' | 'INACTIVE'
}

export type OwnerDailyRecord = {
  id: string
  customerId: string
  productId: string
  recordDate: string
  slot: OwnerDailyRecordSlot
  quantity: number
  unitPriceSnapshot: number | null
  amount: number
  status: OwnerDailyRecordStatus
}

export type OwnerDailyRecordPage = {
  content: OwnerDailyRecord[]
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export type OwnerDailyRecordFilters = {
  recordDate: string
  customerId: string
  productId: string
  slot: '' | OwnerDailyRecordSlot
  status: '' | OwnerDailyRecordStatus
  page?: number
}

export const OWNER_DAILY_RECORD_PAGE_SIZE = 50

function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

function isOwnerCustomerOption(value: unknown): value is OwnerCustomerOption {
  if (typeof value !== 'object' || value === null) return false
  return 'id' in value && typeof value.id === 'string'
    && 'name' in value && typeof value.name === 'string'
    && 'phone' in value && (typeof value.phone === 'string' || value.phone === null)
    && 'status' in value && (value.status === 'ACTIVE' || value.status === 'PAUSED' || value.status === 'INACTIVE')
}

function isOwnerProductOption(value: unknown): value is OwnerProductOption {
  if (typeof value !== 'object' || value === null) return false
  return 'id' in value && typeof value.id === 'string'
    && 'name' in value && typeof value.name === 'string'
    && 'unit' in value && (value.unit === 'LITRE' || value.unit === 'KILOGRAM')
    && 'price' in value && typeof value.price === 'number'
    && 'status' in value && (value.status === 'ACTIVE' || value.status === 'INACTIVE')
}

function isOwnerDailyRecord(value: unknown): value is OwnerDailyRecord {
  if (typeof value !== 'object' || value === null) return false
  return 'id' in value && typeof value.id === 'string'
    && 'customerId' in value && typeof value.customerId === 'string'
    && 'productId' in value && typeof value.productId === 'string'
    && 'recordDate' in value && typeof value.recordDate === 'string' && validDate(value.recordDate)
    && 'slot' in value && (value.slot === 'MORNING' || value.slot === 'EVENING')
    && 'quantity' in value && typeof value.quantity === 'number'
    && 'unitPriceSnapshot' in value && (typeof value.unitPriceSnapshot === 'number' || value.unitPriceSnapshot === null)
    && 'amount' in value && typeof value.amount === 'number'
    && 'status' in value && (value.status === 'COLLECTED' || value.status === 'EXTRA'
      || value.status === 'SKIPPED' || value.status === 'PAUSED')
}

function isOwnerDailyRecordPage(value: unknown): value is OwnerDailyRecordPage {
  if (typeof value !== 'object' || value === null || !('content' in value) || !Array.isArray(value.content)) {
    return false
  }
  return value.content.every(isOwnerDailyRecord)
    && 'number' in value && typeof value.number === 'number'
    && 'size' in value && typeof value.size === 'number'
    && 'totalElements' in value && typeof value.totalElements === 'number'
    && 'totalPages' in value && typeof value.totalPages === 'number'
}

export async function getOwnerCustomers(token: string, options: Parameters<typeof apiRequest>[3] = {}) {
  const body = await apiRequest<unknown>('/owner/customers', token, undefined, {
    ...options,
    fallback: 'Could not load customers.',
  })
  if (!Array.isArray(body) || !body.every(isOwnerCustomerOption)) {
    throw new Error('The server returned invalid customer data.')
  }
  return body
}

export async function getOwnerProducts(token: string, options: Parameters<typeof apiRequest>[3] = {}) {
  const body = await apiRequest<unknown>('/owner/products', token, undefined, {
    ...options,
    fallback: 'Could not load products.',
  })
  if (!Array.isArray(body) || !body.every(isOwnerProductOption)) {
    throw new Error('The server returned invalid product data.')
  }
  return body
}

export async function getOwnerDailyRecords(
  token: string,
  filters: OwnerDailyRecordFilters,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const params = new URLSearchParams({
    recordDate: filters.recordDate,
    page: String(filters.page ?? 0),
    size: String(OWNER_DAILY_RECORD_PAGE_SIZE),
  })
  if (filters.customerId) params.set('customerId', filters.customerId)
  if (filters.productId) params.set('productId', filters.productId)
  if (filters.slot) params.set('slot', filters.slot)
  if (filters.status) params.set('status', filters.status)
  const body = await apiRequest<unknown>(`/owner/daily-records?${params}`, token, undefined, {
    ...options,
    fallback: 'Could not load daily records.',
  })
  if (!isOwnerDailyRecordPage(body)) throw new Error('The server returned invalid daily records.')
  return body
}

export type CreateOwnerDailyRecord = {
  customerId: string
  productId: string
  recordDate: string
  slot: OwnerDailyRecordSlot
  quantity: number
  unitPriceSnapshot: number | null
  status: OwnerDailyRecordStatus
}

export type UpdateOwnerDailyRecord = Pick<CreateOwnerDailyRecord, 'quantity' | 'unitPriceSnapshot' | 'status'>

export async function createOwnerDailyRecord(
  token: string,
  record: CreateOwnerDailyRecord,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>('/owner/daily-records', token, {
    method: 'POST',
    body: JSON.stringify(record),
  }, {
    ...options,
    fallback: 'Could not save the daily record.',
  })
  if (!isOwnerDailyRecord(body)) throw new Error('The server returned an invalid daily record.')
  return body
}

export async function updateOwnerDailyRecord(
  token: string,
  recordId: string,
  record: UpdateOwnerDailyRecord,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>(`/owner/daily-records/${recordId}`, token, {
    method: 'PUT',
    body: JSON.stringify(record),
  }, {
    ...options,
    fallback: 'Could not update the daily record.',
  })
  if (!isOwnerDailyRecord(body)) throw new Error('The server returned an invalid daily record.')
  return body
}

export function ownerDailyRecordErrorMessage(error: unknown) {
  if (error instanceof TypeError) {
    return 'Unable to connect to Milk Web. Check your connection and try again.'
  }
  if (error instanceof Error) return error.message
  return 'Could not load daily records.'
}

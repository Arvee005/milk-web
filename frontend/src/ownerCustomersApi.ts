import { apiRequest } from './authApi.ts'

export type OwnerCustomerStatus = 'ACTIVE' | 'PAUSED' | 'INACTIVE'
export type OwnerBillingType = 'PREPAID' | 'MONTHLY'

export type OwnerCustomer = {
  id: string
  userId: string
  phone: string | null
  name: string
  address: string | null
  billingType: OwnerBillingType
  status: OwnerCustomerStatus
  joinDate: string
}

export type CreateOwnerCustomer = {
  phone: string
  password: string
  name: string
  address: string | null
  billingType: OwnerBillingType
  status: OwnerCustomerStatus
  joinDate: string
}

// The backend update endpoint is a full replacement and rejects a body without
// billingType, status, and joinDate. Phone is intentionally absent: it cannot be
// changed after the customer account is created.
export type UpdateOwnerCustomer = {
  name: string
  address: string | null
  billingType: OwnerBillingType
  status: OwnerCustomerStatus
  joinDate: string
}

export type OwnerCustomerFilters = {
  query: string
  status: '' | OwnerCustomerStatus
}

const CUSTOMER_STATUS_LABELS: Record<OwnerCustomerStatus, string> = {
  ACTIVE: 'Active',
  PAUSED: 'Paused',
  INACTIVE: 'Inactive',
}

const BILLING_TYPE_LABELS: Record<OwnerBillingType, string> = {
  PREPAID: 'Prepaid',
  MONTHLY: 'Monthly',
}

export function customerStatusLabel(status: OwnerCustomerStatus) {
  return CUSTOMER_STATUS_LABELS[status]
}

export function billingTypeLabel(billingType: OwnerBillingType) {
  return BILLING_TYPE_LABELS[billingType]
}

function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

function isOwnerCustomer(value: unknown): value is OwnerCustomer {
  return typeof value === 'object' && value !== null
    && 'id' in value && typeof value.id === 'string'
    && 'userId' in value && typeof value.userId === 'string'
    && 'phone' in value && (typeof value.phone === 'string' || value.phone === null)
    && 'name' in value && typeof value.name === 'string'
    && 'address' in value && (typeof value.address === 'string' || value.address === null)
    && 'billingType' in value && (value.billingType === 'PREPAID' || value.billingType === 'MONTHLY')
    && 'status' in value && (value.status === 'ACTIVE' || value.status === 'PAUSED' || value.status === 'INACTIVE')
    && 'joinDate' in value && typeof value.joinDate === 'string' && validDate(value.joinDate)
}

export async function getOwnerCustomers(
  token: string,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>('/owner/customers', token, undefined, {
    ...options,
    fallback: 'Could not load customers.',
  })
  if (!Array.isArray(body) || !body.every(isOwnerCustomer)) {
    throw new Error('The server returned invalid customer data.')
  }
  return body
}

export async function createOwnerCustomer(
  token: string,
  customer: CreateOwnerCustomer,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>('/owner/customers', token, {
    method: 'POST',
    body: JSON.stringify(customer),
  }, {
    ...options,
    fallback: 'Could not create the customer.',
  })
  if (!isOwnerCustomer(body)) throw new Error('The server returned an invalid customer.')
  return body
}

export async function updateOwnerCustomer(
  token: string,
  customerId: string,
  customer: UpdateOwnerCustomer,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>(`/owner/customers/${customerId}`, token, {
    method: 'PUT',
    body: JSON.stringify(customer),
  }, {
    ...options,
    fallback: 'Could not update the customer.',
  })
  if (!isOwnerCustomer(body)) throw new Error('The server returned an invalid customer.')
  return body
}

// Search and status filtering run in the browser: the list endpoint returns every
// customer unpaginated and offers no query parameters.
export function filterOwnerCustomers(
  customers: OwnerCustomer[],
  filters: OwnerCustomerFilters,
): OwnerCustomer[] {
  const query = filters.query.trim().toLowerCase()
  return customers.filter((customer) => {
    if (filters.status && customer.status !== filters.status) return false
    if (!query) return true
    return customer.name.toLowerCase().includes(query)
      || (customer.phone ?? '').toLowerCase().includes(query)
  })
}

export function ownerCustomerErrorMessage(error: unknown) {
  if (error instanceof TypeError) {
    return 'Unable to connect to Milk Web. Check your connection and try again.'
  }
  if (error instanceof Error) return error.message
  return 'Could not load customers.'
}

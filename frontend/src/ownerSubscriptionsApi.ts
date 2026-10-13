import { apiRequest } from './authApi.ts'

export type OwnerSubscriptionStatus = 'ACTIVE' | 'PAUSED' | 'CANCELLED'

export type OwnerSubscription = {
  id: string
  customerId: string
  productId: string
  defaultQuantity: number
  status: OwnerSubscriptionStatus
  startDate: string
  endDate: string | null
  createdAt: string
  updatedAt: string
}

export type CreateOwnerSubscription = {
  customerId: string
  productId: string
  defaultQuantity: number
  startDate: string
  endDate: string | null
}

export type UpdateOwnerSubscription = {
  defaultQuantity: number
  startDate: string
  endDate: string | null
  status: OwnerSubscriptionStatus
}

export type OwnerSubscriptionFilters = {
  customerId: string | null
  productId: string | null
  status: '' | OwnerSubscriptionStatus
  effectiveOn: string | null
}

const SUBSCRIPTION_STATUS_LABELS: Record<OwnerSubscriptionStatus, string> = {
  ACTIVE: 'Active',
  PAUSED: 'Paused',
  CANCELLED: 'Cancelled',
}

export function ownerSubscriptionStatusLabel(status: OwnerSubscriptionStatus) {
  return SUBSCRIPTION_STATUS_LABELS[status]
}

function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

function validInstant(value: string) {
  return validDate(value.slice(0, 10))
}

function isOwnerSubscription(value: unknown): value is OwnerSubscription {
  return typeof value === 'object' && value !== null
    && 'id' in value && typeof value.id === 'string'
    && 'customerId' in value && typeof value.customerId === 'string'
    && 'productId' in value && typeof value.productId === 'string'
    && 'defaultQuantity' in value && typeof value.defaultQuantity === 'number'
    && 'status' in value && (value.status === 'ACTIVE' || value.status === 'PAUSED' || value.status === 'CANCELLED')
    && 'startDate' in value && typeof value.startDate === 'string' && validDate(value.startDate)
    && 'endDate' in value && (value.endDate === null || (typeof value.endDate === 'string' && validDate(value.endDate)))
    && 'createdAt' in value && typeof value.createdAt === 'string' && validInstant(value.createdAt)
    && 'updatedAt' in value && typeof value.updatedAt === 'string' && validInstant(value.updatedAt)
}

export async function getOwnerSubscriptions(
  token: string,
  filters: OwnerSubscriptionFilters = { customerId: null, productId: null, status: '', effectiveOn: null },
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const params = new URLSearchParams()
  if (filters.customerId) params.set('customerId', filters.customerId)
  if (filters.productId) params.set('productId', filters.productId)
  if (filters.status) params.set('status', filters.status)
  if (filters.effectiveOn) params.set('effectiveOn', filters.effectiveOn)
  const query = params.toString()
  const path = `/owner/subscriptions${query ? `?${query}` : ''}`

  const body = await apiRequest<unknown>(path, token, undefined, {
    ...options,
    fallback: 'Could not load subscriptions.',
  })
  if (!Array.isArray(body) || !body.every(isOwnerSubscription)) {
    throw new Error('The server returned invalid subscription data.')
  }
  return body
}

export async function getOwnerSubscriptionById(
  token: string,
  subscriptionId: string,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>(`/owner/subscriptions/${subscriptionId}`, token, undefined, {
    ...options,
    fallback: 'Could not load the subscription.',
  })
  if (!isOwnerSubscription(body)) throw new Error('The server returned an invalid subscription.')
  return body
}

export async function createOwnerSubscription(
  token: string,
  subscription: CreateOwnerSubscription,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>('/owner/subscriptions', token, {
    method: 'POST',
    body: JSON.stringify(subscription),
  }, {
    ...options,
    fallback: 'Could not create the subscription.',
  })
  if (!isOwnerSubscription(body)) throw new Error('The server returned an invalid subscription.')
  return body
}

export async function updateOwnerSubscription(
  token: string,
  subscriptionId: string,
  subscription: UpdateOwnerSubscription,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>(`/owner/subscriptions/${subscriptionId}`, token, {
    method: 'PUT',
    body: JSON.stringify(subscription),
  }, {
    ...options,
    fallback: 'Could not update the subscription.',
  })
  if (!isOwnerSubscription(body)) throw new Error('The server returned an invalid subscription.')
  return body
}

export function ownerSubscriptionErrorMessage(error: unknown) {
  if (error instanceof TypeError) {
    return 'Unable to connect to Milk Web. Check your connection and try again.'
  }
  if (error instanceof Error) return error.message
  return 'Could not load subscriptions.'
}
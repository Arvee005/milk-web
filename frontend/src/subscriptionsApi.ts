import { apiRequest } from './authApi.ts'

export type SubscriptionStatus = 'ACTIVE' | 'PAUSED' | 'CANCELLED'
export type SubscriptionPeriodState = 'UPCOMING' | 'IN_PERIOD' | 'ENDED'

export type CustomerSubscription = {
  id: string
  productName: string
  productUnit: 'LITRE' | 'KILOGRAM'
  defaultQuantity: number
  status: SubscriptionStatus
  startDate: string
  endDate: string | null
  effectiveToday: boolean
  periodState: SubscriptionPeriodState
}

export type SubscriptionGroups = {
  effective: CustomerSubscription[]
  other: CustomerSubscription[]
}

export type SubscriptionsViewState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'empty' }
  | { status: 'ready'; subscriptions: CustomerSubscription[] }

const statusLabels: Record<SubscriptionStatus, string> = {
  ACTIVE: 'Active',
  PAUSED: 'Paused',
  CANCELLED: 'Cancelled',
}

const periodStateLabels: Record<SubscriptionPeriodState, string> = {
  UPCOMING: 'Starts in the future',
  IN_PERIOD: 'Within date range',
  ENDED: 'Date range ended',
}

export function subscriptionStatusLabel(status: SubscriptionStatus) {
  return statusLabels[status]
}

export function subscriptionPeriodStateLabel(state: SubscriptionPeriodState) {
  return periodStateLabels[state]
}

export function groupCustomerSubscriptions(subscriptions: CustomerSubscription[]): SubscriptionGroups {
  return {
    effective: subscriptions.filter((subscription) => subscription.effectiveToday),
    other: subscriptions.filter((subscription) => !subscription.effectiveToday),
  }
}

export function subscriptionsViewState(
  subscriptions: CustomerSubscription[] | null,
  loading: boolean,
  error: string,
): SubscriptionsViewState {
  if (loading) return { status: 'loading' }
  if (error) return { status: 'error', message: error }
  if (subscriptions === null || subscriptions.length === 0) return { status: 'empty' }
  return { status: 'ready', subscriptions }
}

export function subscriptionErrorMessage(error: unknown) {
  if (error instanceof TypeError) {
    return 'Unable to connect to Radhika Farms. Check your connection and try again.'
  }
  if (error instanceof Error) return error.message
  return 'Could not load your subscriptions.'
}

export async function getCustomerSubscriptions(
  token: string,
  options: Parameters<typeof apiRequest>[3] = {},
): Promise<CustomerSubscription[]> {
  const body = await apiRequest<unknown>(
    '/customer/subscriptions',
    token,
    undefined,
    { ...options, fallback: 'Could not load your subscriptions.' },
  )
  if (!Array.isArray(body) || !body.every(isCustomerSubscription)) {
    throw new Error('The server returned invalid subscription details.')
  }
  return body
}

function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value
}

function isCustomerSubscription(value: unknown): value is CustomerSubscription {
  if (typeof value !== 'object' || value === null) return false
  return 'id' in value && typeof value.id === 'string'
    && 'productName' in value && typeof value.productName === 'string'
    && 'productUnit' in value && (value.productUnit === 'LITRE' || value.productUnit === 'KILOGRAM')
    && 'defaultQuantity' in value && typeof value.defaultQuantity === 'number'
    && 'status' in value && (value.status === 'ACTIVE' || value.status === 'PAUSED' || value.status === 'CANCELLED')
    && 'startDate' in value && typeof value.startDate === 'string' && validDate(value.startDate)
    && 'endDate' in value && (value.endDate === null || typeof value.endDate === 'string' && validDate(value.endDate))
    && 'effectiveToday' in value && typeof value.effectiveToday === 'boolean'
    && 'periodState' in value && (value.periodState === 'UPCOMING' || value.periodState === 'IN_PERIOD'
      || value.periodState === 'ENDED')
}

import { apiRequest } from './authApi.ts'

export type CustomerProfile = {
  id: string
  userId: string
  phone: string | null
  name: string
  address: string | null
  billingType: 'PREPAID' | 'MONTHLY'
  status: 'ACTIVE' | 'PAUSED' | 'INACTIVE' | 'PENDING'
  joinDate: string
}

export type CustomerProfileViewState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'empty' }
  | { status: 'ready'; profile: CustomerProfile }

export function customerProfileViewState(
  profile: CustomerProfile | null,
  loading: boolean,
  error: string,
): CustomerProfileViewState {
  if (loading) return { status: 'loading' }
  if (error) return { status: 'error', message: error }
  return profile ? { status: 'ready', profile } : { status: 'empty' }
}

function isCustomerProfile(value: unknown): value is CustomerProfile {
  if (typeof value !== 'object' || value === null) return false
  return 'id' in value && typeof value.id === 'string'
    && 'userId' in value && typeof value.userId === 'string'
    && 'phone' in value && (typeof value.phone === 'string' || value.phone === null)
    && 'name' in value && typeof value.name === 'string'
    && 'address' in value && (typeof value.address === 'string' || value.address === null)
    && 'billingType' in value && (value.billingType === 'PREPAID' || value.billingType === 'MONTHLY')
    && 'status' in value && (value.status === 'ACTIVE' || value.status === 'PAUSED' || value.status === 'INACTIVE'
      || value.status === 'PENDING')
    && 'joinDate' in value && typeof value.joinDate === 'string'
    && /^\d{4}-\d{2}-\d{2}$/.test(value.joinDate)
}

export async function getCustomerProfile(
  token: string,
  options: Parameters<typeof apiRequest>[3] = {},
): Promise<CustomerProfile> {
  const body = await apiRequest<unknown>(
    '/customer/me',
    token,
    undefined,
    { ...options, fallback: 'Could not load your profile.' },
  )
  if (!isCustomerProfile(body)) {
    throw new Error('The server returned an invalid customer profile.')
  }
  return body
}

export function customerProfileErrorMessage(error: unknown) {
  if (error instanceof TypeError) {
    return 'Unable to connect to Radhika Farms. Check your connection and try again.'
  }
  if (error instanceof Error) return error.message
  return 'Could not load your profile.'
}

import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ApiError } from './authApi.ts'
import {
  customerProfileErrorMessage,
  customerProfileViewState,
  getCustomerProfile,
  type CustomerProfile,
} from './customerApi.ts'

const profile: CustomerProfile = {
  id: 'customer-id',
  userId: 'user-id',
  phone: '9876543210',
  name: 'Test Customer',
  address: 'Synthetic address',
  billingType: 'MONTHLY',
  status: 'ACTIVE',
  joinDate: '2026-09-01',
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('customer profile API', () => {
  it('loads the current customer profile without accepting a customer ID', async () => {
    let requestUrl = ''
    let authorization = ''
    const result = await getCustomerProfile('customer-token', {
      apiBase: '/api',
      fetcher: async (input, init) => {
        requestUrl = String(input)
        authorization = new Headers(init?.headers).get('Authorization') ?? ''
        return jsonResponse(profile)
      },
    })

    assert.equal(requestUrl, '/api/customer/me')
    assert.equal(authorization, 'Bearer customer-token')
    assert.equal(result.name, 'Test Customer')
    assert.equal(result.billingType, 'MONTHLY')
  })

  it('rejects malformed profile responses rather than rendering incomplete data', async () => {
    await assert.rejects(
      getCustomerProfile('customer-token', {
        fetcher: async () => jsonResponse({ id: 'customer-id', name: 'Test Customer' }),
      }),
      /invalid customer profile/i,
    )
  })

  it('preserves authorization failures as a profile error without calling them session expiry', async () => {
    await assert.rejects(
      getCustomerProfile('customer-token', {
        fetcher: async () => jsonResponse({ error: 'Forbidden' }, 403),
      }),
      (error: unknown) => error instanceof ApiError
        && error.status === 403
        && customerProfileErrorMessage(error).includes("don't have permission"),
    )
  })

  it('reports expired sessions distinctly so the workspace can clear them', async () => {
    await assert.rejects(
      getCustomerProfile('expired-token', {
        fetcher: async () => jsonResponse({ error: 'Unauthorized' }, 401),
      }),
      (error: unknown) => error instanceof ApiError
        && error.status === 401
        && customerProfileErrorMessage(error).includes('session has expired'),
    )
  })

  it('provides a clear retryable message for network errors', async () => {
    const error = new TypeError('Failed to fetch')
    assert.equal(
      customerProfileErrorMessage(error),
      'Unable to connect to Milk Web. Check your connection and try again.',
    )
  })

  it('models loading and recoverable error states used by the profile view', () => {
    assert.deepEqual(customerProfileViewState(null, true, ''), { status: 'loading' })
    assert.deepEqual(customerProfileViewState(null, false, 'Access denied'), {
      status: 'error',
      message: 'Access denied',
    })
    assert.deepEqual(customerProfileViewState(null, false, ''), { status: 'empty' })
    assert.deepEqual(customerProfileViewState(profile, false, ''), {
      status: 'ready',
      profile,
    })
  })
})

describe('pending customer profile', () => {
  it('accepts a self-registered PENDING profile', async () => {
    const result = await getCustomerProfile('customer-token', {
      fetcher: async () => new Response(JSON.stringify({
        id: 'customer-id', userId: 'user-id', phone: '9123456780', name: 'Asha', address: null,
        billingType: 'MONTHLY', status: 'PENDING', joinDate: '2026-10-15',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    })
    assert.equal(result.status, 'PENDING')
  })
})

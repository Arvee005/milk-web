import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ApiError } from './authApi.ts'
import {
  getCustomerSubscriptions,
  groupCustomerSubscriptions,
  subscriptionErrorMessage,
  subscriptionPeriodStateLabel,
  subscriptionStatusLabel,
  subscriptionsViewState,
  type CustomerSubscription,
} from './subscriptionsApi.ts'

const subscription = (overrides: Partial<CustomerSubscription> = {}): CustomerSubscription => ({
  id: 'subscription-id',
  productName: 'Cow milk',
  productUnit: 'LITRE',
  defaultQuantity: 1.5,
  status: 'ACTIVE',
  startDate: '2026-10-03',
  endDate: null,
  effectiveToday: true,
  periodState: 'IN_PERIOD',
  ...overrides,
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('customer subscriptions', () => {
  it('keeps status separate from the inclusive date-range effectiveness', () => {
    const subscriptions = [
      subscription(),
      subscription({ id: 'upcoming', startDate: '2026-10-04', effectiveToday: false, periodState: 'UPCOMING' }),
      subscription({ id: 'paused', status: 'PAUSED', effectiveToday: false }),
      subscription({ id: 'ended', endDate: '2026-10-02', effectiveToday: false, periodState: 'ENDED' }),
    ]
    const groups = groupCustomerSubscriptions(subscriptions)
    assert.deepEqual(groups.effective.map(({ id }) => id), ['subscription-id'])
    assert.deepEqual(groups.other.map(({ id }) => id), ['upcoming', 'paused', 'ended'])
    assert.equal(subscriptionStatusLabel('CANCELLED'), 'Cancelled')
    assert.equal(subscriptionPeriodStateLabel('IN_PERIOD'), 'Within date range')
  })

  it('models loading, empty, errors, and subscription data', () => {
    assert.deepEqual(subscriptionsViewState(null, true, ''), { status: 'loading' })
    assert.deepEqual(subscriptionsViewState([], false, ''), { status: 'empty' })
    assert.deepEqual(subscriptionsViewState(null, false, 'Try again'), { status: 'error', message: 'Try again' })
    assert.deepEqual(subscriptionsViewState([subscription()], false, ''), {
      status: 'ready',
      subscriptions: [subscription()],
    })
  })

  it('requests the customer-scoped endpoint with bearer auth and no customer identifier', async () => {
    let requestUrl = ''
    let authorization = ''
    const result = await getCustomerSubscriptions('customer-token', {
      apiBase: '/api',
      fetcher: async (input, init) => {
        requestUrl = String(input)
        authorization = new Headers(init?.headers).get('Authorization') ?? ''
        return jsonResponse([subscription()])
      },
    })
    assert.equal(requestUrl, '/api/customer/subscriptions')
    assert.equal(authorization.startsWith('Bearer '), true)
    assert.equal(authorization.length > 'Bearer '.length, true)
    assert.equal(requestUrl.includes('customerId'), false)
    assert.equal(result[0].productName, 'Cow milk')
  })

  it('surfaces authorization, invalid response, and network errors', async () => {
    for (const status of [401, 403]) {
      await assert.rejects(
        getCustomerSubscriptions('customer-token', {
          fetcher: async () => jsonResponse({ error: 'Request failed' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
    }
    await assert.rejects(
      getCustomerSubscriptions('customer-token', {
        fetcher: async () => jsonResponse([{ ...subscription(), effectiveToday: 'yes' }]),
      }),
      /invalid subscription details/,
    )
    assert.match(subscriptionErrorMessage(new TypeError('Failed to fetch')), /connection/)
  })
})

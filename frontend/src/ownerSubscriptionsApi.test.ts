import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ApiError } from './authApi.ts'
import {
  createOwnerSubscription,
  getOwnerSubscriptionById,
  getOwnerSubscriptions,
  ownerSubscriptionErrorMessage,
  ownerSubscriptionStatusLabel,
  updateOwnerSubscription,
  type OwnerSubscription,
  type CreateOwnerSubscription,
  type UpdateOwnerSubscription,
} from './ownerSubscriptionsApi.ts'

const subscription = (overrides: Partial<OwnerSubscription> = {}): OwnerSubscription => ({
  id: 'subscription-id',
  customerId: 'customer-id',
  productId: 'product-id',
  defaultQuantity: 1.5,
  status: 'ACTIVE',
  startDate: '2026-10-01',
  endDate: null,
  createdAt: '2026-08-01T04:00:00Z',
  updatedAt: '2026-08-01T04:00:00Z',
  ...overrides,
})

const createPayload = (overrides: Partial<CreateOwnerSubscription> = {}): CreateOwnerSubscription => ({
  customerId: 'customer-id',
  productId: 'product-id',
  defaultQuantity: 1.5,
  startDate: '2026-10-01',
  endDate: null,
  ...overrides,
})

const updatePayload = (overrides: Partial<UpdateOwnerSubscription> = {}): UpdateOwnerSubscription => ({
  defaultQuantity: 1.5,
  startDate: '2026-10-01',
  endDate: null,
  status: 'ACTIVE',
  ...overrides,
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('owner subscriptions API client', () => {
  it('requests the subscription list with no filters', async () => {
    let requestUrl = ''
    const results = await getOwnerSubscriptions('owner-token', { customerId: null, productId: null, status: '', effectiveOn: null }, {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse([subscription(), subscription({ id: 'second', status: 'PAUSED' })])
      },
    })
    assert.equal(requestUrl, '/api/owner/subscriptions')
    assert.equal(results.length, 2)
    assert.equal(results[0].defaultQuantity, 1.5)
    assert.equal(results[0].endDate, null)
    assert.equal(results[1].status, 'PAUSED')
  })

  it('requests the subscription list with customerId filter', async () => {
    let requestUrl = ''
    await getOwnerSubscriptions('owner-token', { customerId: 'customer-123', productId: null, status: '', effectiveOn: null }, {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse([subscription()])
      },
    })
    assert.equal(requestUrl, '/api/owner/subscriptions?customerId=customer-123')
  })

  it('requests the subscription list with productId filter', async () => {
    let requestUrl = ''
    await getOwnerSubscriptions('owner-token', { customerId: null, productId: 'product-123', status: '', effectiveOn: null }, {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse([subscription()])
      },
    })
    assert.equal(requestUrl, '/api/owner/subscriptions?productId=product-123')
  })

  it('requests the subscription list with status filter', async () => {
    let requestUrl = ''
    await getOwnerSubscriptions('owner-token', { customerId: null, productId: null, status: 'ACTIVE', effectiveOn: null }, {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse([subscription()])
      },
    })
    assert.equal(requestUrl, '/api/owner/subscriptions?status=ACTIVE')
  })

  it('requests the subscription list with effectiveOn filter', async () => {
    let requestUrl = ''
    await getOwnerSubscriptions('owner-token', { customerId: null, productId: null, status: '', effectiveOn: '2026-10-15' }, {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse([subscription()])
      },
    })
    assert.equal(requestUrl, '/api/owner/subscriptions?effectiveOn=2026-10-15')
  })

  it('requests the subscription list with multiple filters', async () => {
    let requestUrl = ''
    await getOwnerSubscriptions('owner-token', { customerId: 'customer-123', productId: 'product-456', status: 'ACTIVE', effectiveOn: '2026-10-15' }, {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse([subscription()])
      },
    })
    assert.equal(requestUrl, '/api/owner/subscriptions?customerId=customer-123&productId=product-456&status=ACTIVE&effectiveOn=2026-10-15')
  })

  it('gets a subscription by ID', async () => {
    let requestUrl = ''
    const result = await getOwnerSubscriptionById('owner-token', 'subscription-id', {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse(subscription())
      },
    })
    assert.equal(requestUrl, '/api/owner/subscriptions/subscription-id')
    assert.equal(result.id, 'subscription-id')
    assert.equal(result.defaultQuantity, 1.5)
    assert.equal(result.endDate, null)
  })

  it('creates a subscription without a status field', async () => {
    let sentBody = ''
    let method = ''
    const created = await createOwnerSubscription('owner-token', createPayload(), {
      apiBase: '/api',
      fetcher: async (input, init) => {
        method = init?.method ?? ''
        sentBody = String(init?.body)
        assert.equal(String(input), '/api/owner/subscriptions')
        return jsonResponse(subscription(), 201)
      },
    })
    assert.equal(method, 'POST')
    assert.deepEqual(JSON.parse(sentBody), {
      customerId: 'customer-id',
      productId: 'product-id',
      defaultQuantity: 1.5,
      startDate: '2026-10-01',
      endDate: null,
    })
    assert.equal('status' in JSON.parse(sentBody), false)
    assert.equal(created.status, 'ACTIVE')
  })

  it('creates a subscription with endDate explicitly set to null', async () => {
    let sentBody = ''
    await createOwnerSubscription('owner-token', createPayload({ endDate: null }), {
      fetcher: async (_input, init) => {
        sentBody = String(init?.body)
        return jsonResponse(subscription({ endDate: null }), 201)
      },
    })
    assert.equal(JSON.parse(sentBody).endDate, null)
  })

  it('creates a subscription with a specific endDate', async () => {
    let sentBody = ''
    await createOwnerSubscription('owner-token', createPayload({ endDate: '2026-12-31' }), {
      fetcher: async (_input, init) => {
        sentBody = String(init?.body)
        return jsonResponse(subscription({ endDate: '2026-12-31' }), 201)
      },
    })
    assert.equal(JSON.parse(sentBody).endDate, '2026-12-31')
  })

  it('handles create errors and preserves 401, 403, 409 statuses', async () => {
    for (const status of [401, 403, 409]) {
      await assert.rejects(
        createOwnerSubscription('owner-token', createPayload(), {
          fetcher: async () => jsonResponse({ error: 'Subscription already exists' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
    }
  })

  it('updates a subscription with required fields including status', async () => {
    let sentBody = ''
    let method = ''
    let requestUrl = ''
    await updateOwnerSubscription('owner-token', 'subscription-id', updatePayload({
      defaultQuantity: 2.0,
      startDate: '2026-11-01',
      endDate: '2026-12-31',
      status: 'PAUSED',
    }), {
      apiBase: '/api',
      fetcher: async (input, init) => {
        method = init?.method ?? ''
        requestUrl = String(input)
        sentBody = String(init?.body)
        return jsonResponse(subscription({ defaultQuantity: 2.0, startDate: '2026-11-01', endDate: '2026-12-31', status: 'PAUSED' }))
      },
    })
    assert.equal(method, 'PUT')
    assert.equal(requestUrl, '/api/owner/subscriptions/subscription-id')
    assert.deepEqual(JSON.parse(sentBody), {
      defaultQuantity: 2.0,
      startDate: '2026-11-01',
      endDate: '2026-12-31',
      status: 'PAUSED',
    })
  })

  it('handles update errors and preserves 401, 403, 409 statuses', async () => {
    for (const status of [401, 403, 409]) {
      await assert.rejects(
        updateOwnerSubscription('owner-token', 'subscription-id', updatePayload(), {
          fetcher: async () => jsonResponse({ error: 'Subscription not found' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
    }
  })

  it('rejects malformed subscription responses', async () => {
    await assert.rejects(
      getOwnerSubscriptions('owner-token', { customerId: null, productId: null, status: '', effectiveOn: null }, {
        fetcher: async () => jsonResponse([{ ...subscription(), status: 'ARCHIVED' }]),
      }),
      /invalid subscription data/,
    )
    await assert.rejects(
      getOwnerSubscriptions('owner-token', { customerId: null, productId: null, status: '', effectiveOn: null }, {
        fetcher: async () => jsonResponse([{ ...subscription(), defaultQuantity: 'one' }]),
      }),
      /invalid subscription data/,
    )
    await assert.rejects(
      getOwnerSubscriptionById('owner-token', 'subscription-id', {
        fetcher: async () => jsonResponse({ ...subscription(), endDate: 'not-a-date' }),
      }),
      /invalid subscription/,
    )
  })

  it('rejects empty and malformed timestamps', async () => {
    for (const malformed of ['', 'bad', '2026-13-45']) {
      await assert.rejects(
        getOwnerSubscriptions('owner-token', { customerId: null, productId: null, status: '', effectiveOn: null }, {
          fetcher: async () => jsonResponse([{ ...subscription(), updatedAt: malformed }]),
        }),
        /invalid subscription data/,
      )
      await assert.rejects(
        getOwnerSubscriptions('owner-token', { customerId: null, productId: null, status: '', effectiveOn: null }, {
          fetcher: async () => jsonResponse([{ ...subscription(), createdAt: malformed }]),
        }),
        /invalid subscription data/,
      )
    }
    await assert.rejects(
      createOwnerSubscription('owner-token', createPayload(), {
        fetcher: async () => jsonResponse({ ...subscription(), updatedAt: '2026-02-30T00:00:00Z' }, 201),
      }),
      /invalid subscription/,
    )
    await assert.rejects(
      updateOwnerSubscription('owner-token', 'subscription-id', updatePayload(), {
        fetcher: async () => jsonResponse({ ...subscription(), updatedAt: '' }),
      }),
      /invalid subscription/,
    )
    const accepted = await getOwnerSubscriptions('owner-token', { customerId: null, productId: null, status: '', effectiveOn: null }, {
      fetcher: async () => jsonResponse([subscription({ updatedAt: '2026-12-31T18:30:00Z' })]),
    })
    assert.equal(accepted[0].updatedAt, '2026-12-31T18:30:00Z')
  })

  it('renders human labels for subscription status', () => {
    assert.equal(ownerSubscriptionStatusLabel('ACTIVE'), 'Active')
    assert.equal(ownerSubscriptionStatusLabel('PAUSED'), 'Paused')
    assert.equal(ownerSubscriptionStatusLabel('CANCELLED'), 'Cancelled')
  })

  it('handles network errors with a user-friendly message', () => {
    assert.match(ownerSubscriptionErrorMessage(new TypeError('Failed to fetch')), /connection/)
  })
})
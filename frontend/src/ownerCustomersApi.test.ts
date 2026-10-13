import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ApiError } from './authApi.ts'
import {
  billingTypeLabel,
  createOwnerCustomer,
  customerStatusLabel,
  filterOwnerCustomers,
  getOwnerCustomers,
  ownerCustomerErrorMessage,
  updateOwnerCustomer,
  type OwnerCustomer,
  type UpdateOwnerCustomer,
} from './ownerCustomersApi.ts'

const customer = (overrides: Partial<OwnerCustomer> = {}): OwnerCustomer => ({
  id: 'customer-id',
  userId: 'user-id',
  phone: '9400000000',
  name: 'Synthetic Customer',
  address: '1 Test Lane',
  billingType: 'MONTHLY',
  status: 'ACTIVE',
  joinDate: '2026-08-01',
  ...overrides,
})

const replacement = (overrides: Partial<UpdateOwnerCustomer> = {}): UpdateOwnerCustomer => ({
  name: 'Synthetic Customer',
  address: '1 Test Lane',
  billingType: 'MONTHLY',
  status: 'ACTIVE',
  joinDate: '2026-08-01',
  ...overrides,
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('owner customers API client', () => {
  it('requests the full customer list and returns every management field', async () => {
    let requestUrl = ''
    const results = await getOwnerCustomers('owner-token', {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse([customer(), customer({ id: 'second', name: 'Second Customer', status: 'PAUSED' })])
      },
    })
    assert.equal(requestUrl, '/api/owner/customers')
    assert.equal(results.length, 2)
    assert.equal(results[0].address, '1 Test Lane')
    assert.equal(results[0].billingType, 'MONTHLY')
    assert.equal(results[0].joinDate, '2026-08-01')
    assert.equal(results[1].status, 'PAUSED')
  })

  it('creates a customer with an initial password and the exact create payload', async () => {
    let sentBody = ''
    let method = ''
    const created = await createOwnerCustomer('owner-token', {
      name: 'Synthetic Customer',
      phone: '9400000000',
      password: 'initial-secret',
      address: '1 Test Lane',
      billingType: 'MONTHLY',
      status: 'ACTIVE',
      joinDate: '2026-08-01',
    }, {
      apiBase: '/api',
      fetcher: async (input, init) => {
        method = init?.method ?? ''
        sentBody = String(init?.body)
        assert.equal(String(input), '/api/owner/customers')
        return jsonResponse(customer(), 201)
      },
    })
    assert.equal(method, 'POST')
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Synthetic Customer',
      phone: '9400000000',
      password: 'initial-secret',
      address: '1 Test Lane',
      billingType: 'MONTHLY',
      status: 'ACTIVE',
      joinDate: '2026-08-01',
    })
    assert.equal(created.id, 'customer-id')
  })

  it('sends a null address when the owner leaves it blank', async () => {
    let sentBody = ''
    await createOwnerCustomer('owner-token', {
      name: 'No Address',
      phone: '9400000001',
      password: 'initial-secret',
      address: null,
      billingType: 'PREPAID',
      status: 'ACTIVE',
      joinDate: '2026-08-01',
    }, {
      fetcher: async (_input, init) => {
        sentBody = String(init?.body)
        return jsonResponse(customer({ address: null, billingType: 'PREPAID' }), 201)
      },
    })
    assert.equal(JSON.parse(sentBody).address, null)
  })

  it('updates with every field the full-replacement contract requires and no phone', async () => {
    let sentBody = ''
    let method = ''
    let requestUrl = ''
    await updateOwnerCustomer('owner-token', 'customer-id', replacement({
      name: 'Renamed Customer',
      address: null,
      billingType: 'PREPAID',
      status: 'INACTIVE',
      joinDate: '2026-09-15',
    }), {
      apiBase: '/api',
      fetcher: async (input, init) => {
        method = init?.method ?? ''
        requestUrl = String(input)
        sentBody = String(init?.body)
        return jsonResponse(customer({ name: 'Renamed Customer', status: 'INACTIVE', billingType: 'PREPAID' }))
      },
    })
    assert.equal(method, 'PUT')
    assert.equal(requestUrl, '/api/owner/customers/customer-id')
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Renamed Customer',
      address: null,
      billingType: 'PREPAID',
      status: 'INACTIVE',
      joinDate: '2026-09-15',
    })
    assert.equal('phone' in JSON.parse(sentBody), false)
    assert.equal('password' in JSON.parse(sentBody), false)
  })

  it('preserves 401, 403, and 409 statuses and rejects malformed customers', async () => {
    for (const status of [401, 403, 409]) {
      await assert.rejects(
        createOwnerCustomer('owner-token', {
          name: 'Synthetic Customer',
          phone: '9400000000',
          password: 'initial-secret',
          address: null,
          billingType: 'MONTHLY',
          status: 'ACTIVE',
          joinDate: '2026-08-01',
        }, {
          fetcher: async () => jsonResponse({ error: 'Phone number is already registered.' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
      await assert.rejects(
        updateOwnerCustomer('owner-token', 'customer-id', replacement(), {
          fetcher: async () => jsonResponse({ error: 'Forbidden.' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
    }
    await assert.rejects(
      getOwnerCustomers('owner-token', {
        fetcher: async () => jsonResponse([{ ...customer(), status: 'ARCHIVED' }]),
      }),
      /invalid customer data/,
    )
    await assert.rejects(
      getOwnerCustomers('owner-token', {
        fetcher: async () => jsonResponse([{ ...customer(), joinDate: '01-08-2026' }]),
      }),
      /invalid customer data/,
    )
    await assert.rejects(
      updateOwnerCustomer('owner-token', 'customer-id', replacement(), {
        fetcher: async () => jsonResponse({ ...customer(), billingType: 'DAILY' }),
      }),
      /invalid customer/,
    )
    assert.match(ownerCustomerErrorMessage(new TypeError('Failed to fetch')), /connection/)
  })

  it('filters the loaded list by name, phone, and status without another request', () => {
    const customers = [
      customer(),
      customer({ id: 'second', name: 'Ravi Kumar', phone: '9500000000', status: 'PAUSED' }),
      customer({ id: 'third', name: 'Sunita Devi', phone: '9600000000', status: 'INACTIVE' }),
    ]
    assert.deepEqual(filterOwnerCustomers(customers, { query: '', status: '' }).length, 3)
    assert.deepEqual(
      filterOwnerCustomers(customers, { query: 'ravi', status: '' }).map((item) => item.id),
      ['second'],
    )
    assert.deepEqual(
      filterOwnerCustomers(customers, { query: '96000', status: '' }).map((item) => item.id),
      ['third'],
    )
    assert.deepEqual(
      filterOwnerCustomers(customers, { query: '', status: 'PAUSED' }).map((item) => item.id),
      ['second'],
    )
    assert.deepEqual(
      filterOwnerCustomers(customers, { query: 'ravi', status: 'ACTIVE' }).map((item) => item.id),
      [],
    )
  })

  it('renders human labels for customer status and billing type', () => {
    assert.equal(customerStatusLabel('ACTIVE'), 'Active')
    assert.equal(customerStatusLabel('PAUSED'), 'Paused')
    assert.equal(customerStatusLabel('INACTIVE'), 'Inactive')
    assert.equal(billingTypeLabel('MONTHLY'), 'Monthly')
    assert.equal(billingTypeLabel('PREPAID'), 'Prepaid')
  })
})

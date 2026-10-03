import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ApiError } from './authApi.ts'
import {
  createOwnerDailyRecord,
  getOwnerCustomers,
  getOwnerDailyRecords,
  getOwnerProducts,
  ownerDailyRecordErrorMessage,
  updateOwnerDailyRecord,
  OWNER_DAILY_RECORD_PAGE_SIZE,
  type OwnerDailyRecord,
  type OwnerDailyRecordFilters,
} from './ownerDailyRecordsApi.ts'

const customer = {
  id: 'customer-id',
  name: 'Synthetic Customer',
  phone: '9400000000',
  status: 'ACTIVE',
}

const product = {
  id: 'product-id',
  name: 'Cow milk',
  unit: 'LITRE',
  price: 60,
  status: 'ACTIVE',
}

const record = (overrides: Partial<OwnerDailyRecord> = {}): OwnerDailyRecord => ({
  id: 'record-id',
  customerId: customer.id,
  productId: product.id,
  recordDate: '2026-10-03',
  slot: 'MORNING',
  quantity: 2,
  unitPriceSnapshot: 60,
  amount: 120,
  status: 'COLLECTED',
  ...overrides,
})

const filters = (overrides: Partial<OwnerDailyRecordFilters> = {}): OwnerDailyRecordFilters => ({
  recordDate: '2026-10-03',
  customerId: '',
  productId: '',
  slot: '',
  status: '',
  ...overrides,
})

const page = (content: OwnerDailyRecord[], number = 0, totalPages = 1) => ({
  content,
  number,
  size: OWNER_DAILY_RECORD_PAGE_SIZE,
  totalElements: content.length,
  totalPages,
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('owner daily records API client', () => {
  it('requests owner customers and products for entry labels and price prefill', async () => {
    const urls: string[] = []
    const options = {
      apiBase: '/api',
      fetcher: async (input: RequestInfo | URL) => {
        const url = String(input)
        urls.push(url)
        return url.endsWith('/owner/products') ? jsonResponse([product]) : jsonResponse([customer])
      },
    }
    assert.equal((await getOwnerCustomers('owner-token', options))[0].name, 'Synthetic Customer')
    assert.equal((await getOwnerProducts('owner-token', options))[0].price, 60)
    assert.deepEqual(urls, ['/api/owner/customers', '/api/owner/products'])
  })

  it('sends only the active owner filters and the requested page', async () => {
    let requestUrl = ''
    const result = await getOwnerDailyRecords('owner-token', filters({
      customerId: customer.id,
      slot: 'EVENING',
      status: 'EXTRA',
      page: 2,
    }), {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse(page([record()], 2, 3))
      },
    })
    const query = new URLSearchParams(requestUrl.slice(requestUrl.indexOf('?') + 1))
    assert.equal(query.get('recordDate'), '2026-10-03')
    assert.equal(query.get('customerId'), customer.id)
    assert.equal(query.get('slot'), 'EVENING')
    assert.equal(query.get('status'), 'EXTRA')
    assert.equal(query.get('page'), '2')
    assert.equal(query.get('size'), String(OWNER_DAILY_RECORD_PAGE_SIZE))
    assert.equal(query.has('productId'), false)
    assert.equal(query.has('fromDate'), false)
    assert.equal(result.totalPages, 3)
    assert.equal(result.content[0].amount, 120)
  })

  it('creates a record with an explicit unit-price snapshot and no subscription', async () => {
    let sentBody = ''
    let method = ''
    const created = await createOwnerDailyRecord('owner-token', {
      customerId: customer.id,
      productId: product.id,
      recordDate: '2026-10-03',
      slot: 'MORNING',
      quantity: 2.5,
      unitPriceSnapshot: 65.5,
      status: 'EXTRA',
    }, {
      apiBase: '/api',
      fetcher: async (_input, init) => {
        method = init?.method ?? ''
        sentBody = String(init?.body)
        return jsonResponse(record({ quantity: 2.5, unitPriceSnapshot: 65.5, amount: 163.75, status: 'EXTRA' }), 201)
      },
    })
    assert.equal(method, 'POST')
    assert.deepEqual(JSON.parse(sentBody), {
      customerId: customer.id,
      productId: product.id,
      recordDate: '2026-10-03',
      slot: 'MORNING',
      quantity: 2.5,
      unitPriceSnapshot: 65.5,
      status: 'EXTRA',
    })
    assert.equal(created.amount, 163.75)
  })

  it('updates quantity, status, and price snapshot without resending identity fields', async () => {
    let sentBody = ''
    let method = ''
    await updateOwnerDailyRecord('owner-token', 'record-id', {
      quantity: 0,
      unitPriceSnapshot: null,
      status: 'SKIPPED',
    }, {
      apiBase: '/api',
      fetcher: async (input, init) => {
        method = init?.method ?? ''
        sentBody = String(init?.body)
        assert.equal(String(input), '/api/owner/daily-records/record-id')
        return jsonResponse(record({ quantity: 0, unitPriceSnapshot: null, amount: 0, status: 'SKIPPED' }))
      },
    })
    assert.equal(method, 'PUT')
    assert.deepEqual(JSON.parse(sentBody), { quantity: 0, unitPriceSnapshot: null, status: 'SKIPPED' })
  })

  it('rejects malformed records, duplicates, and other API failures without masking their status', async () => {
    for (const status of [401, 403, 409]) {
      await assert.rejects(
        createOwnerDailyRecord('owner-token', {
          customerId: customer.id,
          productId: product.id,
          recordDate: '2026-10-03',
          slot: 'MORNING',
          quantity: 2,
          unitPriceSnapshot: 60,
          status: 'COLLECTED',
        }, {
          fetcher: async () => jsonResponse({ error: 'Conflict for this slot.' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
    }
    await assert.rejects(
      getOwnerDailyRecords('owner-token', filters(), {
        fetcher: async () => jsonResponse(page([{ ...record(), slot: 'MIDDAY' as OwnerDailyRecord['slot'] }])),
      }),
      /invalid daily records/,
    )
    await assert.rejects(
      getOwnerProducts('owner-token', {
        fetcher: async () => jsonResponse([{ ...product, unit: 'POUND' }]),
      }),
      /invalid product data/,
    )
    assert.match(ownerDailyRecordErrorMessage(new TypeError('Failed to fetch')), /connection/)
  })
})
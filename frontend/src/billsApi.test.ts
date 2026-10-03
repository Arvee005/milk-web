import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ApiError } from './authApi.ts'
import {
  billErrorMessage,
  billStatusLabel,
  billsViewState,
  getCustomerBills,
  type CustomerBillPage,
} from './billsApi.ts'

const page: CustomerBillPage = {
  content: [{
    id: 'bill-id',
    period: '2026-09',
    periodStart: '2026-09-01',
    periodEnd: '2026-09-30',
    totalAmount: 135,
    status: 'ISSUED',
    issuedAt: '2026-10-01T10:30:00Z',
    lines: [{
      recordDate: '2026-09-03',
      slot: 'MORNING',
      description: 'Cow milk (LITRE)',
      quantity: 1.5,
      unitPrice: 60,
      amount: 90,
    }],
  }],
  number: 0,
  size: 10,
  totalElements: 1,
  totalPages: 1,
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('customer bills', () => {
  it('labels issued status without inferring any payment state', () => {
    assert.equal(billStatusLabel('ISSUED'), 'Issued')
    assert.equal('paymentStatus' in page.content[0], false)
  })

  it('models loading, empty, errors, and bill list states', () => {
    assert.deepEqual(billsViewState(null, true, ''), { status: 'loading' })
    assert.deepEqual(billsViewState({ ...page, content: [] }, false, ''), { status: 'empty' })
    assert.deepEqual(billsViewState(null, false, 'Try again'), { status: 'error', message: 'Try again' })
    assert.deepEqual(billsViewState(page, false, ''), { status: 'ready', page })
  })

  it('loads a paged customer bill response without any customer identifier', async () => {
    let requestUrl = ''
    let authorization = ''
    const result = await getCustomerBills('customer-token', 0, 10, {
      apiBase: '/api',
      fetcher: async (input, init) => {
        requestUrl = String(input)
        authorization = new Headers(init?.headers).get('Authorization') ?? ''
        return jsonResponse(page)
      },
    })
    assert.equal(requestUrl, '/api/customer/bills?page=0&size=10')
    assert.equal(requestUrl.includes('customerId'), false)
    assert.equal(authorization.startsWith('Bearer '), true)
    assert.equal(result.content[0].period, '2026-09')
    assert.equal(result.content[0].totalAmount, 135)
    assert.equal(result.content[0].lines[0].amount, 90)
  })

  it('rejects invalid pagination and reports auth, response, and network failures', async () => {
    await assert.rejects(getCustomerBills('token', -1), /Page must be zero or greater/)
    await assert.rejects(getCustomerBills('token', 0, 101), /Page size must be between/)
    for (const status of [401, 403]) {
      await assert.rejects(
        getCustomerBills('token', 0, 10, { fetcher: async () => jsonResponse({}, status) }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
    }
    await assert.rejects(
      getCustomerBills('token', 0, 10, { fetcher: async () => jsonResponse({ ...page, content: [
        { ...page.content[0], status: 'DRAFT' },
      ] }) }),
      /invalid bill details/,
    )
    assert.match(billErrorMessage(new TypeError('Failed to fetch')), /connection/)
  })

  it('can retry a failed request and then return issued bill data', async () => {
    let attempts = 0
    const fetcher: typeof fetch = async () => {
      attempts += 1
      return attempts === 1
        ? jsonResponse({ error: 'Temporary failure' }, 503)
        : jsonResponse(page)
    }
    await assert.rejects(getCustomerBills('token', 0, 10, { fetcher }), ApiError)
    const retried = await getCustomerBills('token', 0, 10, { fetcher })
    assert.equal(attempts, 2)
    assert.equal(billsViewState(retried, false, '').status, 'ready')
  })
})

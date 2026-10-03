import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ApiError } from './authApi.ts'
import {
  dailyCollectionSlots,
  dailyRecordStatusLabel,
  dailyRecordErrorMessage,
  getCustomerDailyRecords,
  recordsViewState,
  validateDailyRecordFilters,
  type CustomerDailyRecord,
} from './dailyRecordsApi.ts'

const record = (overrides: Partial<CustomerDailyRecord> = {}): CustomerDailyRecord => ({
  id: 'record-id',
  recordDate: '2026-10-03',
  slot: 'MORNING',
  productName: 'Cow milk',
  productUnit: 'LITRE',
  quantity: 1.5,
  unitPriceSnapshot: 60,
  amount: 90,
  status: 'COLLECTED',
  ...overrides,
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('customer daily records', () => {
  it('groups actual records into morning and evening without inventing a missing slot', () => {
    const slots = dailyCollectionSlots([record()])
    assert.deepEqual(slots.map(({ slot, records }) => [slot, records.length]), [
      ['MORNING', 1],
      ['EVENING', 0],
    ])
    assert.equal(slots[1].records.length, 0)
  })

  it('labels all supported record statuses distinctly', () => {
    assert.equal(dailyRecordStatusLabel('COLLECTED'), 'Collected')
    assert.equal(dailyRecordStatusLabel('EXTRA'), 'Extra milk')
    assert.equal(dailyRecordStatusLabel('SKIPPED'), 'Skipped')
    assert.equal(dailyRecordStatusLabel('PAUSED'), 'Paused')
  })

  it('models loading, errors, empty results, and actual records', () => {
    assert.deepEqual(recordsViewState([], true, ''), { status: 'loading' })
    assert.deepEqual(recordsViewState([], false, 'Try again'), { status: 'error', message: 'Try again' })
    assert.deepEqual(recordsViewState([], false, ''), { status: 'empty' })
    assert.deepEqual(recordsViewState([record()], false, ''), { status: 'ready', records: [record()] })
  })

  it('validates specific-date and date-range filters', () => {
    assert.equal(validateDailyRecordFilters({ recordDate: '2026-10-03' }), '')
    assert.equal(validateDailyRecordFilters({ fromDate: '2026-10-01', toDate: '2026-10-03' }), '')
    assert.match(validateDailyRecordFilters({ recordDate: '2026-10-03', fromDate: '2026-10-01' }), /not both/)
    assert.match(validateDailyRecordFilters({ fromDate: '2026-10-01' }), /both a start and end/)
    assert.match(validateDailyRecordFilters({ fromDate: '2026-10-04', toDate: '2026-10-03' }), /on or before/)
    assert.match(validateDailyRecordFilters({ fromDate: '2025-10-02', toDate: '2026-10-03' }), /366/)
    assert.match(validateDailyRecordFilters({ recordDate: '2026-02-30' }), /valid date/)
    assert.match(validateDailyRecordFilters({ page: -1 }), /zero or greater/)
    assert.match(validateDailyRecordFilters({ size: 101 }), /between 1 and 100/)
  })

  it('requests only the current customer endpoint with filters and bearer auth', async () => {
    let requestUrl = ''
    let authorization = ''
    const response = await getCustomerDailyRecords('customer-token', {
      fromDate: '2026-10-01',
      toDate: '2026-10-03',
      slot: 'EVENING',
      status: 'EXTRA',
      page: 0,
      size: 20,
    }, {
      apiBase: '/api',
      fetcher: async (input, init) => {
        requestUrl = String(input)
        authorization = new Headers(init?.headers).get('Authorization') ?? ''
        return jsonResponse({
          content: [record({ slot: 'EVENING', status: 'EXTRA', unitPriceSnapshot: 70, amount: 105 })],
          totalPages: 1,
          totalElements: 1,
          number: 0,
          size: 20,
        })
      },
    })

    assert.equal(requestUrl, '/api/customer/daily-records?fromDate=2026-10-01&toDate=2026-10-03&slot=EVENING&status=EXTRA&page=0&size=20')
    assert.equal(authorization, 'Bearer customer-token')
    assert.equal(response.content[0].status, 'EXTRA')
    assert.equal(requestUrl.includes('customerId'), false)
  })

  it('does not make requests for invalid filters', async () => {
    let requested = false
    await assert.rejects(
      getCustomerDailyRecords('customer-token', { fromDate: '2026-10-01' }, {
        fetcher: async () => {
          requested = true
          return jsonResponse({})
        },
      }),
      /both a start and end/,
    )
    assert.equal(requested, false)
  })

  it('surfaces expired, forbidden, and network errors without masking their meaning', async () => {
    const responseError = async (status: number) => {
      await assert.rejects(
        getCustomerDailyRecords('customer-token', { recordDate: '2026-10-03' }, {
          fetcher: async () => jsonResponse({ error: 'Request failed' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
    }
    await responseError(401)
    await responseError(403)
    assert.match(dailyRecordErrorMessage(new TypeError('Failed to fetch')), /connection/)
  })
})

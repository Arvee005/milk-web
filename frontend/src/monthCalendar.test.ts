import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import type { CustomerDailyRecord } from './dailyRecordsApi.ts'
import {
  calendarCells,
  daysInMonth,
  getCustomerMonthRecords,
  monthDateRange,
  monthOf,
  shiftMonth,
  summarizeMonth,
} from './monthCalendar.ts'

function record(overrides: Partial<CustomerDailyRecord> = {}): CustomerDailyRecord {
  return {
    id: 'record-' + Math.random().toString(36).slice(2),
    recordDate: '2026-10-01',
    slot: 'MORNING',
    productName: 'Cow milk',
    productUnit: 'LITRE',
    quantity: 1,
    unitPriceSnapshot: 60,
    amount: 60,
    status: 'COLLECTED',
    ...overrides,
  }
}

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('month arithmetic', () => {
  it('moves across year boundaries', () => {
    assert.equal(shiftMonth('2026-01', -1), '2025-12')
    assert.equal(shiftMonth('2026-12', 1), '2027-01')
    assert.equal(shiftMonth('2026-10', -12), '2025-10')
    assert.equal(monthOf('2026-10-15'), '2026-10')
  })

  it('knows month lengths, including leap years', () => {
    assert.equal(daysInMonth('2026-02'), 28)
    assert.equal(daysInMonth('2028-02'), 29)
    assert.equal(daysInMonth('2026-09'), 30)
    assert.equal(daysInMonth('2026-10'), 31)
  })

  it('loads the current month only up to today and never a future month', () => {
    assert.deepEqual(monthDateRange('2026-10', '2026-10-15'), { fromDate: '2026-10-01', toDate: '2026-10-15' })
    assert.deepEqual(monthDateRange('2026-09', '2026-10-15'), { fromDate: '2026-09-01', toDate: '2026-09-30' })
    assert.equal(monthDateRange('2026-11', '2026-10-15'), null)
  })
})

describe('loading a month of records', () => {
  it('follows every page for the month', async () => {
    const urls: string[] = []
    const fetcher: typeof fetch = async (input) => {
      const url = new URL(String(input), 'http://localhost')
      urls.push(url.pathname + url.search)
      const page = Number(url.searchParams.get('page'))
      return jsonResponse({
        content: [record({ id: `page-${page}` })],
        totalPages: 2,
        totalElements: 2,
        number: page,
        size: 100,
      })
    }
    const records = await getCustomerMonthRecords('token', '2026-09', '2026-10-15', { fetcher, apiBase: '/api' })

    assert.deepEqual(records.map((r) => r.id), ['page-0', 'page-1'])
    assert.equal(urls.length, 2)
    for (const [index, url] of urls.entries()) {
      assert.match(url, /^\/api\/customer\/daily-records\?/)
      assert.match(url, /fromDate=2026-09-01/)
      assert.match(url, /toDate=2026-09-30/)
      assert.match(url, new RegExp(`page=${index}`))
      assert.match(url, /size=100/)
      assert.doesNotMatch(url, /customerId/)
    }
  })

  it('does not request a future month', async () => {
    let called = false
    const fetcher: typeof fetch = async () => {
      called = true
      return jsonResponse({})
    }
    assert.deepEqual(await getCustomerMonthRecords('token', '2026-11', '2026-10-15', { fetcher }), [])
    assert.equal(called, false)
  })
})

describe('month summary', () => {
  it('counts only collected and extra records, separating litres and kilograms', () => {
    const summary = summarizeMonth([
      record({ quantity: 1, amount: 60 }),
      record({ status: 'EXTRA', quantity: 2, amount: 120 }),
      record({ productName: 'Buffalo milk', quantity: 0.5, amount: 37.5, slot: 'EVENING' }),
      record({ status: 'SKIPPED', quantity: 0, amount: 0, unitPriceSnapshot: null }),
      record({ status: 'PAUSED', quantity: 0, amount: 0, unitPriceSnapshot: null }),
      record({ productName: 'Cow ghee', productUnit: 'KILOGRAM', quantity: 0.25, amount: 225 }),
    ])
    assert.deepEqual(summary, { litres: 3.5, kilograms: 0.25, amount: 442.5, collections: 4, skipped: 1 })
  })

  it('rounds away floating-point noise', () => {
    const summary = summarizeMonth([record({ quantity: 0.1, amount: 0.1 }), record({ quantity: 0.2, amount: 0.2 })])
    assert.equal(summary.amount, 0.3)
    assert.equal(summary.litres, 0.3)
  })
})

describe('calendar cells', () => {
  it('starts weeks on Monday and marks today and future days', () => {
    // 1 October 2026 is a Thursday, so three blank cells come first.
    const cells = calendarCells('2026-10', '2026-10-15', [])
    assert.deepEqual(cells.slice(0, 4).map((c) => c.kind), ['blank', 'blank', 'blank', 'day'])
    const days = cells.filter((c) => c.kind === 'day')
    assert.equal(days.length, 31)
    const fifteenth = days[14]
    const sixteenth = days[15]
    assert.ok(fifteenth.kind === 'day' && fifteenth.today && !fifteenth.future)
    assert.ok(sixteenth.kind === 'day' && sixteenth.future && !sixteenth.today)
  })

  it('groups each day’s records with the morning first', () => {
    const cells = calendarCells('2026-10', '2026-10-15', [
      record({ id: 'evening', recordDate: '2026-10-02', slot: 'EVENING' }),
      record({ id: 'morning', recordDate: '2026-10-02', slot: 'MORNING' }),
      record({ id: 'other-day', recordDate: '2026-10-03' }),
    ])
    const second = cells.find((c) => c.kind === 'day' && c.date === '2026-10-02')
    assert.ok(second && second.kind === 'day')
    assert.deepEqual(second.records.map((r) => r.id), ['morning', 'evening'])
  })
})

import assert from 'node:assert/strict'
import { after, afterEach, describe, it } from 'node:test'
import { JSDOM } from 'jsdom'
import { createElement } from 'react'
import { ApiError } from './authApi.ts'
import type { CustomerBill, CustomerBillPage } from './billsApi.ts'
import type { CustomerDailyRecord } from './dailyRecordsApi.ts'
import CustomerMonth from './CustomerMonth.tsx'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
Object.assign(globalThis, {
  window: dom.window,
  document: dom.window.document,
  HTMLElement: dom.window.HTMLElement,
  IS_REACT_ACT_ENVIRONMENT: true,
})
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: dom.window.navigator })

const { cleanup, fireEvent, render, screen, waitFor } = await import('@testing-library/react')

const TODAY = '2026-10-15'

function record(overrides: Partial<CustomerDailyRecord>): CustomerDailyRecord {
  return {
    id: 'r-' + Math.random().toString(36).slice(2),
    recordDate: '2026-10-15',
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

const october = [
  record({ recordDate: '2026-10-15', quantity: 1, amount: 60 }),
  record({ recordDate: '2026-10-14', quantity: 2, amount: 120, status: 'EXTRA' }),
  record({ recordDate: '2026-10-14', slot: 'EVENING', productName: 'Buffalo milk', quantity: 0, amount: 0, unitPriceSnapshot: null, status: 'SKIPPED' }),
]
const september = [record({ recordDate: '2026-09-30', quantity: 1.5, amount: 90 })]

const bill: CustomerBill = {
  id: 'bill-1', period: '2026-09', periodStart: '2026-09-01', periodEnd: '2026-09-30',
  totalAmount: 2730, status: 'ISSUED', issuedAt: '2026-10-01T04:00:00Z', lines: [],
}
const billPage = (content: CustomerBill[]): CustomerBillPage => ({ content, number: 0, size: 1, totalElements: content.length, totalPages: content.length })

type Options = {
  records?: (month: string) => Promise<CustomerDailyRecord[]>
  bills?: CustomerBill[]
  joinDate?: string | null
  account?: { amountDue: number; credit: number } | 'error'
}

function show(options: Options = {}) {
  const requestedMonths: string[] = []
  let billsOpened = 0
  let expired = 0
  const loadRecords = async (_token: string, month: string) => {
    requestedMonths.push(month)
    return options.records ? options.records(month) : month === '2026-10' ? october : month === '2026-09' ? september : []
  }
  const loadBills = async () => billPage(options.bills ?? [bill])
  const loadAccount = async () => {
    if (options.account === 'error' || options.account === undefined) throw new Error('No account in this test')
    return { customerId: 'c', customerName: 'A', currency: 'INR', totalBilled: 0, totalReceived: 0, bills: [], ...options.account }
  }
  render(createElement(CustomerMonth, {
    token: 'token',
    today: TODAY,
    joinDate: options.joinDate === undefined ? '2026-09-01' : options.joinDate,
    onSessionExpired: () => { expired += 1 },
    onOpenBills: () => { billsOpened += 1 },
    loadRecords: loadRecords as never,
    loadBills: loadBills as never,
    loadAccount: loadAccount as never,
  }))
  return { requestedMonths, billsOpened: () => billsOpened, expired: () => expired }
}

afterEach(() => cleanup())
after(() => dom.window.close())

describe('customer month view', { concurrency: false }, () => {
  it('summarises the current month and shows today’s records', async () => {
    show()
    await screen.findByText('3')
    assert.ok(screen.getByRole('heading', { name: 'October so far' }))
    assert.match(document.body.textContent ?? '', /₹180\.00/)
    assert.match(document.body.textContent ?? '', /2 collections/)
    assert.ok(screen.getByRole('heading', { name: 'Thursday, 15 October' }))
    assert.equal(screen.getByRole('button', { name: /^Thursday, 15 October/ }).getAttribute('aria-pressed'), 'true')
  })

  it('shows a tapped day’s collections, including skipped ones', async () => {
    show()
    await screen.findByText('3')
    fireEvent.click(screen.getByRole('button', { name: /^Wednesday, 14 October: morning extra milk, evening skipped/ }))
    assert.ok(screen.getByRole('heading', { name: 'Wednesday, 14 October' }))
    assert.ok(screen.getByText('Morning · Cow milk'))
    assert.ok(screen.getByText('Evening · Buffalo milk'))
    const detail = screen.getByRole('heading', { name: 'Wednesday, 14 October' }).parentElement
    assert.match(detail?.textContent ?? '', /2 L · ₹120\.00Extra milk/)
    assert.match(detail?.textContent ?? '', /Evening · Buffalo milkSkipped/)
  })

  it('does not allow future days to be chosen', async () => {
    show()
    await screen.findByText('3')
    assert.equal((screen.getByRole('button', { name: /^Friday, 16 October/ }) as HTMLButtonElement).disabled, true)
  })

  it('moves between months within the account’s history', async () => {
    const view = show()
    await screen.findByText('3')
    const previous = screen.getByRole('button', { name: 'Previous month' }) as HTMLButtonElement
    const next = screen.getByRole('button', { name: 'Next month' }) as HTMLButtonElement
    assert.equal(next.disabled, true)

    fireEvent.click(previous)
    await screen.findByText('1.5')
    assert.ok(screen.getByRole('heading', { name: 'September at a glance' }))
    assert.match(document.body.textContent ?? '', /Month total/)
    assert.deepEqual(view.requestedMonths, ['2026-10', '2026-09'])
    // The account started in September, so there is nothing earlier to show.
    assert.equal(previous.disabled, true)
    assert.equal(next.disabled, false)
  })

  it('links to the latest issued bill', async () => {
    const view = show()
    await screen.findByText('September 2026', { exact: false })
    assert.match(document.body.textContent ?? '', /₹2,730\.00/)
    fireEvent.click(screen.getByRole('button', { name: 'See bill' }))
    assert.equal(view.billsOpened(), 1)
  })

  it('shows the balance due when the account is available', async () => {
    const view = show({ account: { amountDue: 1110, credit: 0 } })
    assert.ok(await screen.findByText('Balance due'))
    assert.match(document.body.textContent ?? '', /₹1,110\.00/)
    assert.match(document.body.textContent ?? '', /Latest: September 2026 ₹2,730\.00/)
    fireEvent.click(screen.getByRole('button', { name: 'See bill' }))
    assert.equal(view.billsOpened(), 1)
  })

  it('shows credit instead when nothing is owed', async () => {
    show({ account: { amountDue: 0, credit: 625 } })
    assert.ok(await screen.findByText('Credit on your account'))
    assert.match(document.body.textContent ?? '', /₹625\.00/)
    assert.match(document.body.textContent ?? '', /Used on your next bill/)
  })

  it('says when no bill has been issued yet', async () => {
    show({ bills: [] })
    assert.ok(await screen.findByText('No bills issued yet'))
  })

  it('shows an error with a retry, and expires the session on 401', async () => {
    let attempts = 0
    show({
      records: async () => {
        attempts += 1
        if (attempts === 1) throw new ApiError('Could not load collection records.', 500)
        return october
      },
    })
    assert.match((await screen.findByRole('alert')).textContent ?? '', /Could not load/)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByText('3')

    cleanup()
    const view = show({ records: async () => { throw new ApiError('Your session has expired.', 401) } })
    await waitFor(() => assert.equal(view.expired(), 1))
  })
})

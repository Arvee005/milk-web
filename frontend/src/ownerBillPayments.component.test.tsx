import assert from 'node:assert/strict'
import { after, afterEach, describe, it } from 'node:test'
import { JSDOM } from 'jsdom'
import { createElement } from 'react'
import { ApiError } from './authApi.ts'
import type { Account, PaymentResult, Transaction } from './ledgerApi.ts'
import OwnerBillPayments from './OwnerBillPayments.tsx'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
Object.assign(globalThis, {
  window: dom.window,
  document: dom.window.document,
  HTMLElement: dom.window.HTMLElement,
  IS_REACT_ACT_ENVIRONMENT: true,
})
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: dom.window.navigator })

const { cleanup, fireEvent, render, screen, waitFor, within } = await import('@testing-library/react')

function bill(billId: string, period: string, due: number) {
  return {
    billId, customerId: 'c1', customerName: 'Ramesh Kumar', period, periodStart: `${period}-01`, issuedAt: `${period}-28T00:00:00Z`,
    billTotal: due, charges: 0, credits: 0, amountToPay: due, paid: 0, due, status: 'UNPAID' as const, entries: [],
  }
}

function account(overrides: Partial<Account> = {}): Account {
  return {
    customerId: 'c1', customerName: 'Ramesh Kumar', currency: 'INR', amountDue: 3375, credit: 0, totalBilled: 3375,
    totalReceived: 0, bills: [bill('sep', '2026-09', 2250), bill('aug', '2026-08', 1125)], ...overrides,
  }
}

const payment: Transaction = {
  id: 'pay-1', type: 'PAYMENT', date: '2026-10-05', recordedAt: '2026-10-05T05:00:00Z', amount: 1500, method: 'CASH',
  reference: null, reason: null, note: 'At the gate', billId: null, billPeriod: null, paymentId: 'pay-1', reversed: false,
}

type Calls = { payments: { body: unknown; key: string }[]; reversals: { id: string; reason: string }[]; refunds: unknown[] }

function show(options: {
  account?: Account
  history?: Transaction[]
  payment?: (attempt: number) => Promise<PaymentResult>
  refund?: () => Promise<unknown>
  accountError?: unknown
} = {}) {
  const calls: Calls = { payments: [], reversals: [], refunds: [] }
  let changed = 0
  let expired = 0
  let attempts = 0
  const api = {
    getOwnerAccount: async () => {
      if (options.accountError) throw options.accountError
      return options.account ?? account()
    },
    getOwnerTransactions: async () => options.history ?? [payment],
    recordPayment: async (_token: string, _customerId: string, body: unknown, key: string) => {
      calls.payments.push({ body, key })
      attempts += 1
      return options.payment ? options.payment(attempts) : {
        id: 'new', amount: 4000, keptAsCredit: 625, account: { amountDue: 0, credit: 625 },
        appliedTo: [{ billId: 'aug', period: '2026-08', amount: 1125 }, { billId: 'sep', period: '2026-09', amount: 2250 }],
      }
    },
    recordAdjustment: async () => ({}),
    recordRefund: async (...args: unknown[]) => {
      calls.refunds.push(args)
      return options.refund ? options.refund() : {}
    },
    reversePayment: async (_token: string, id: string, reason: string) => {
      calls.reversals.push({ id, reason })
      return {}
    },
  }
  render(createElement(OwnerBillPayments, {
    token: 'owner-token', customerId: 'c1', customerName: 'Ramesh Kumar', billId: 'sep',
    onChanged: () => { changed += 1 }, onSessionExpired: () => { expired += 1 }, api: api as never,
  }))
  return { calls, changed: () => changed, expired: () => expired }
}

afterEach(() => cleanup())
after(() => dom.window.close())

describe('owner bill payments', { concurrency: false }, () => {
  it('shows what the customer owes and previews which bills a payment clears', async () => {
    show()
    assert.match((await screen.findByText(/Owes/)).textContent ?? '', /₹3,375\.00/)
    const amount = screen.getByLabelText('Amount (₹)') as HTMLInputElement
    await waitFor(() => assert.equal(amount.value, '3375.00'))

    fireEvent.change(amount, { target: { value: '4000' } })
    const preview = screen.getByText('This payment will:').parentElement?.textContent ?? ''
    assert.match(preview, /Clear August 2026₹1,125\.00/)
    assert.match(preview, /Clear September 2026₹2,250\.00/)
    assert.match(preview, /Keep as credit for the next bill₹625\.00/)

    fireEvent.change(amount, { target: { value: '1500' } })
    assert.match(screen.getByText('This payment will:').parentElement?.textContent ?? '', /Pay towards September 2026₹375\.00/)
  })

  it('saves a payment once, reusing the request key after a failure and using a new one after success', async () => {
    const view = show({
      payment: async (attempt) => {
        if (attempt === 1) throw new TypeError('Failed to fetch')
        return { id: 'new', amount: 4000, keptAsCredit: 625, account: { amountDue: 0, credit: 625 },
          appliedTo: [{ billId: 'aug', period: '2026-08', amount: 1125 }, { billId: 'sep', period: '2026-09', amount: 2250 }] }
      },
    })
    await screen.findByText(/Owes/)
    fireEvent.change(screen.getByLabelText('Amount (₹)'), { target: { value: '4000' } })
    fireEvent.change(screen.getByLabelText('Method'), { target: { value: 'UPI' } })
    fireEvent.change(screen.getByLabelText('UPI reference (optional)'), { target: { value: 'UTR55' } })

    fireEvent.click(screen.getByRole('button', { name: 'Save payment' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /Unable to connect/)
    fireEvent.click(screen.getByRole('button', { name: 'Save payment' }))
    const notice = await screen.findByRole('status')
    assert.match(notice.textContent ?? '', /Saved ₹4,000\.00 from Ramesh\. Applied to August, September\. ₹625\.00 kept as credit\./)

    assert.equal(view.calls.payments.length, 2)
    assert.equal(view.calls.payments[0].key, view.calls.payments[1].key)
    const sent = view.calls.payments[1].body as { amount: number; method: string; reference: string; receivedOn: string }
    assert.equal(sent.amount, 4000)
    assert.equal(sent.method, 'UPI')
    assert.equal(sent.reference, 'UTR55')
    assert.match(sent.receivedOn, /^\d{4}-\d{2}-\d{2}$/)
    assert.equal(view.changed(), 1)

    fireEvent.change(screen.getByLabelText('Amount (₹)'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save payment' }))
    await waitFor(() => assert.equal(view.calls.payments.length, 3))
    assert.notEqual(view.calls.payments[2].key, view.calls.payments[1].key)
  })

  it('offers returning money only when there is credit, and shows the server’s rule', async () => {
    cleanup()
    show()
    await screen.findByText(/Owes/)
    assert.equal(screen.queryByRole('tab', { name: 'Return credit' }), null)
    cleanup()

    show({
      account: account({ amountDue: 0, credit: 50 }),
      refund: async () => { throw new ApiError('Only ₹50.00 of credit is available to return.', 409) },
    })
    await screen.findByText(/Nothing owed/)
    fireEvent.click(screen.getByRole('tab', { name: 'Return credit' }))
    assert.equal((screen.getByLabelText('Money returned (₹)') as HTMLInputElement).value, '50.00')
    fireEvent.change(screen.getByLabelText('Money returned (₹)'), { target: { value: '60' } })
    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'Moved away' } })
    fireEvent.click(screen.getByRole('button', { name: 'Record money returned' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /Only ₹50\.00/)
  })

  it('corrects a payment with a reason from the history', async () => {
    const view = show()
    await screen.findByText(/Owes/)
    fireEvent.click(screen.getByRole('tab', { name: 'History' }))
    const item = screen.getByText('Payment').closest('li') as HTMLElement
    assert.match(item.textContent ?? '', /At the gate/)
    fireEvent.click(within(item).getByRole('button', { name: 'Correct this payment' }))
    fireEvent.change(within(item).getByLabelText('Why is this payment wrong?'), { target: { value: 'Typed 1500 not 150' } })
    fireEvent.click(within(item).getByRole('button', { name: 'Correct payment' }))

    await waitFor(() => assert.deepEqual(view.calls.reversals, [{ id: 'pay-1', reason: 'Typed 1500 not 150' }]))
    assert.match((await screen.findByRole('status')).textContent ?? '', /Payment corrected/)
  })

  it('expires the session on 401 and shows other load errors with a retry', async () => {
    const view = show({ accountError: new ApiError('Your session has expired.', 401) })
    await waitFor(() => assert.equal(view.expired(), 1))
    cleanup()

    show({ accountError: new ApiError('Could not load the account.', 500) })
    assert.match((await screen.findByRole('alert')).textContent ?? '', /Could not load the account/)
    assert.ok(screen.getByRole('button', { name: 'Try again' }))
  })
})

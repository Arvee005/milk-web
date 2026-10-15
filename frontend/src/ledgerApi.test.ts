import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  billPaymentStatusLabel,
  getCustomerAccount,
  previewPayment,
  recordPayment,
  recordRefund,
  reversePayment,
} from './ledgerApi.ts'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const bills = [
  { billId: 'sep', period: '2026-09', periodStart: '2026-09-01', due: 2250 },
  { billId: 'aug', period: '2026-08', periodStart: '2026-08-01', due: 1125 },
  { billId: 'jul', period: '2026-07', periodStart: '2026-07-01', due: 0 },
]

describe('payment preview', () => {
  it('clears the oldest unpaid bill first and keeps the rest as credit', () => {
    assert.deepEqual(previewPayment(bills, 4000), {
      parts: [
        { billId: 'aug', period: '2026-08', amount: 1125, clears: true },
        { billId: 'sep', period: '2026-09', amount: 2250, clears: true },
      ],
      credit: 625,
    })
  })

  it('shows a part payment on the next bill', () => {
    assert.deepEqual(previewPayment(bills, 1500).parts.map((part) => [part.billId, part.amount, part.clears]),
      [['aug', 1125, true], ['sep', 375, false]])
    assert.equal(previewPayment(bills, 1500).credit, 0)
  })

  it('works in paise so decimals stay exact', () => {
    const preview = previewPayment([{ billId: 'a', period: '2026-08', periodStart: '2026-08-01', due: 0.3 }], 0.1 + 0.2 + 0.05)
    assert.equal(preview.parts[0].amount, 0.3)
    assert.equal(preview.credit, 0.05)
  })

  it('keeps everything as credit when nothing is owed', () => {
    assert.deepEqual(previewPayment([], 500), { parts: [], credit: 500 })
  })
})

describe('ledger API client', () => {
  it('sends a payment with its request key and only UPI references', async () => {
    let url = ''
    let init: RequestInit | undefined
    const result = await recordPayment('owner-token', 'customer-1',
      { amount: 1500, method: 'CASH', reference: 'ignored', note: ' at gate ', receivedOn: '2026-10-05' }, 'key-12345678', {
        apiBase: '/api',
        fetcher: async (input, requestInit) => {
          url = String(input)
          init = requestInit
          return jsonResponse({ id: 'p1', amount: 1500, appliedTo: [], keptAsCredit: 0, account: { amountDue: 0, credit: 0 } }, 201)
        },
      })

    assert.equal(url, '/api/owner/customers/customer-1/payments')
    assert.equal(new Headers(init?.headers).get('Idempotency-Key'), 'key-12345678')
    assert.equal(new Headers(init?.headers).get('Authorization'), 'Bearer owner-token')
    assert.deepEqual(JSON.parse(String(init?.body)), {
      amount: '1500.00', method: 'CASH', reference: null, note: 'at gate', receivedOn: '2026-10-05',
    })
    assert.equal(result.id, 'p1')
  })

  it('validates amounts and reasons before sending anything', async () => {
    let called = false
    const fetcher: typeof fetch = async () => {
      called = true
      return jsonResponse({})
    }
    await assert.rejects(recordPayment('t', 'c', { amount: 0, method: 'CASH' }, 'key-12345678', { fetcher }), /above zero/)
    await assert.rejects(recordPayment('t', 'c', { amount: 10.005, method: 'CASH' }, 'key-12345678', { fetcher }), /2 decimal/)
    await assert.rejects(recordRefund('t', 'c', { amount: 5, method: 'CASH', reason: '  ' }, 'key-12345678', { fetcher }), /reason/)
    await assert.rejects(reversePayment('t', 'p', ' ', { fetcher }), /reason/)
    assert.equal(called, false)
  })

  it('shows the server message when a rule is broken', async () => {
    await assert.rejects(
      recordRefund('t', 'c', { amount: 50, method: 'CASH', reason: 'Moved away' }, 'key-12345678', {
        fetcher: async () => jsonResponse({ error: 'Only ₹20.00 of credit is available to return.' }, 409),
      }),
      /Only ₹20\.00/,
    )
  })

  it('rejects a malformed account response', async () => {
    await assert.rejects(getCustomerAccount('t', { fetcher: async () => jsonResponse({ amountDue: 'lots' }) }), /invalid account/)
  })

  it('labels payment statuses in plain words', () => {
    assert.equal(billPaymentStatusLabel('PART_PAID'), 'Part paid')
    assert.equal(billPaymentStatusLabel('UNPAID'), 'Unpaid')
  })
})

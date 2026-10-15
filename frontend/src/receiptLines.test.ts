import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { formatRupees } from './money.ts'
import { groupBillLines, type ReceiptLine } from './receiptLines.ts'

function line(overrides: Partial<ReceiptLine>): ReceiptLine {
  return { recordDate: '2026-09-01', slot: 'MORNING', description: 'Cow milk (LITRE)', quantity: 1, unitPrice: 60, amount: 60, ...overrides }
}

describe('rupee formatting', () => {
  it('uses the rupee sign, two decimals and Indian digit grouping', () => {
    assert.equal(formatRupees(0), '₹0.00')
    assert.equal(formatRupees(82.5), '₹82.50')
    assert.equal(formatRupees(1234.5), '₹1,234.50')
    assert.equal(formatRupees(123456.78), '₹1,23,456.78')
  })
})

describe('receipt line groups', () => {
  it('totals each product separately with its collection count', () => {
    const groups = groupBillLines([
      line({ recordDate: '2026-09-01' }),
      line({ recordDate: '2026-09-02', quantity: 2, amount: 120 }),
      line({ description: 'Buffalo milk (LITRE)', slot: 'EVENING', quantity: 0.5, unitPrice: 75, amount: 37.5 }),
    ])
    assert.deepEqual(groups, [
      { description: 'Buffalo milk (LITRE)', unitPrice: 75, quantity: 0.5, amount: 37.5, collections: 1 },
      { description: 'Cow milk (LITRE)', unitPrice: 60, quantity: 3, amount: 180, collections: 2 },
    ])
  })

  it('keeps a mid-month price change as its own line so each line is quantity × one price', () => {
    const groups = groupBillLines([
      line({ recordDate: '2026-09-01', unitPrice: 60, amount: 60 }),
      line({ recordDate: '2026-09-20', unitPrice: 65, amount: 65 }),
      line({ recordDate: '2026-09-21', unitPrice: 65, amount: 65 }),
    ])
    assert.deepEqual(groups.map((g) => [g.unitPrice, g.quantity, g.amount, g.collections]), [[60, 1, 60, 1], [65, 2, 130, 2]])
  })

  it('rounds away floating-point noise in the totals', () => {
    const [group] = groupBillLines([line({ quantity: 0.1, amount: 0.1 }), line({ quantity: 0.2, amount: 0.2 })])
    assert.equal(group.quantity, 0.3)
    assert.equal(group.amount, 0.3)
  })

  it('returns nothing for a bill without lines', () => {
    assert.deepEqual(groupBillLines([]), [])
  })
})

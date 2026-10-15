/** One charged collection on a bill, as both the customer and owner bill APIs return it. */
export type ReceiptLine = {
  recordDate: string
  slot: 'MORNING' | 'EVENING' | string
  description: string
  quantity: number
  unitPrice: number
  amount: number
}

export type ReceiptGroup = {
  description: string
  unitPrice: number
  quantity: number
  amount: number
  collections: number
}

/**
 * Totals bill lines per product and price. Lines with the same product but a different price (a price change
 * during the month) stay separate, so every group is exactly "quantity × one price". The amounts come from the
 * bill's own line snapshots, never from current product prices.
 */
export function groupBillLines(lines: ReceiptLine[]): ReceiptGroup[] {
  const groups = new Map<string, ReceiptGroup>()
  for (const line of lines) {
    const key = `${line.description}\u0000${line.unitPrice}`
    const group = groups.get(key) ?? { description: line.description, unitPrice: line.unitPrice, quantity: 0, amount: 0, collections: 0 }
    group.quantity += line.quantity
    group.amount += line.amount
    group.collections += 1
    groups.set(key, group)
  }
  return [...groups.values()]
    .map((group) => ({
      ...group,
      quantity: Math.round(group.quantity * 1000) / 1000,
      amount: Math.round(group.amount * 100) / 100,
    }))
    .sort((a, b) => a.description.localeCompare(b.description) || a.unitPrice - b.unitPrice)
}

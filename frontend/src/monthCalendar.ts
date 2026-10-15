import { apiRequest } from './authApi.ts'
import { getCustomerDailyRecords, type CustomerDailyRecord } from './dailyRecordsApi.ts'

/** A calendar month as `YYYY-MM`. Dates are India business dates as `YYYY-MM-DD`. */
export type Month = string

export type MonthSummary = {
  litres: number
  kilograms: number
  amount: number
  collections: number
  skipped: number
}

export type CalendarCell =
  | { kind: 'blank'; key: string }
  | { kind: 'day'; key: string; date: string; day: number; future: boolean; today: boolean; records: CustomerDailyRecord[] }

const PAGE_SIZE = 100
// A month has at most 31 days x 2 slots per product; 10 pages of 100 covers far more than any real customer.
const MAX_PAGES = 10

export function monthOf(date: string): Month {
  return date.slice(0, 7)
}

export function shiftMonth(month: Month, delta: number): Month {
  const [year, monthNumber] = month.split('-').map(Number)
  const shifted = new Date(Date.UTC(year, monthNumber - 1 + delta, 1))
  return `${shifted.getUTCFullYear()}-${String(shifted.getUTCMonth() + 1).padStart(2, '0')}`
}

export function daysInMonth(month: Month) {
  const [year, monthNumber] = month.split('-').map(Number)
  return new Date(Date.UTC(year, monthNumber, 0)).getUTCDate()
}

/** The dates to load for a month: the whole month, or up to today for the current month. */
export function monthDateRange(month: Month, today: string): { fromDate: string; toDate: string } | null {
  if (month > monthOf(today)) return null
  const lastDay = `${month}-${String(daysInMonth(month)).padStart(2, '0')}`
  return { fromDate: `${month}-01`, toDate: lastDay < today ? lastDay : today }
}

/** Loads every record in the month, following pages, because one page holds at most 100 records. */
export async function getCustomerMonthRecords(
  token: string,
  month: Month,
  today: string,
  options: Parameters<typeof apiRequest>[3] = {},
): Promise<CustomerDailyRecord[]> {
  const range = monthDateRange(month, today)
  if (!range) return []
  const records: CustomerDailyRecord[] = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = await getCustomerDailyRecords(token, { ...range, page, size: PAGE_SIZE }, options)
    records.push(...result.content)
    if (page + 1 >= result.totalPages) break
  }
  return records
}

const chargeable = (record: CustomerDailyRecord) => record.status === 'COLLECTED' || record.status === 'EXTRA'

export function summarizeMonth(records: CustomerDailyRecord[]): MonthSummary {
  const summary: MonthSummary = { litres: 0, kilograms: 0, amount: 0, collections: 0, skipped: 0 }
  for (const record of records) {
    if (record.status === 'SKIPPED') summary.skipped += 1
    if (!chargeable(record)) continue
    summary.collections += 1
    summary.amount += record.amount
    if (record.productUnit === 'LITRE') summary.litres += record.quantity
    else summary.kilograms += record.quantity
  }
  // Round once at the end so repeated decimal additions cannot show values like 2729.9999999.
  summary.amount = Math.round(summary.amount * 100) / 100
  summary.litres = Math.round(summary.litres * 1000) / 1000
  summary.kilograms = Math.round(summary.kilograms * 1000) / 1000
  return summary
}

/** Monday-first calendar cells for the month, each day carrying its records in slot order. */
export function calendarCells(month: Month, today: string, records: CustomerDailyRecord[]): CalendarCell[] {
  const byDate = new Map<string, CustomerDailyRecord[]>()
  for (const record of records) {
    const list = byDate.get(record.recordDate) ?? []
    list.push(record)
    byDate.set(record.recordDate, list)
  }
  const [year, monthNumber] = month.split('-').map(Number)
  const leading = (new Date(Date.UTC(year, monthNumber - 1, 1)).getUTCDay() + 6) % 7
  const cells: CalendarCell[] = Array.from({ length: leading }, (_, index) => ({ kind: 'blank', key: `blank-${index}` }))
  for (let day = 1; day <= daysInMonth(month); day++) {
    const date = `${month}-${String(day).padStart(2, '0')}`
    const dayRecords = (byDate.get(date) ?? []).slice().sort((a, b) =>
      a.slot === b.slot ? a.productName.localeCompare(b.productName) : a.slot === 'MORNING' ? -1 : 1)
    cells.push({ kind: 'day', key: date, date, day, future: date > today, today: date === today, records: dayRecords })
  }
  return cells
}

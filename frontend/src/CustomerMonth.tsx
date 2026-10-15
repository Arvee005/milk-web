import { useCallback, useEffect, useRef, useState } from 'react'
import { isUnauthenticatedError } from './authApi'
import { getCustomerBills, type CustomerBill } from './billsApi'
import { getCustomerAccount, type Account } from './ledgerApi'
import { dailyRecordErrorMessage, dailyRecordStatusLabel, type CustomerDailyRecord } from './dailyRecordsApi'
import {
  calendarCells,
  getCustomerMonthRecords,
  monthOf,
  shiftMonth,
  summarizeMonth,
  type Month,
} from './monthCalendar'

type CustomerMonthProps = {
  token: string
  today: string
  joinDate: string | null
  onSessionExpired: () => void
  onOpenBills: () => void
  loadRecords?: typeof getCustomerMonthRecords
  loadBills?: typeof getCustomerBills
  loadAccount?: typeof getCustomerAccount
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

function monthName(month: Month) {
  return new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${month}-01T00:00:00Z`))
}

function dayName(date: string) {
  return new Intl.DateTimeFormat('en-IN', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' })
    .format(new Date(`${date}T00:00:00Z`))
}

function rupees(value: number) {
  const [whole, paise] = value.toFixed(2).split('.')
  return { whole: '₹' + Number(whole).toLocaleString('en-IN'), paise: '.' + paise }
}

function quantity(value: number) {
  return new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 }).format(value)
}

function unitLabel(record: CustomerDailyRecord) {
  return record.productUnit === 'LITRE' ? 'L' : 'kg'
}

export default function CustomerMonth({
  token,
  today,
  joinDate,
  onSessionExpired,
  onOpenBills,
  loadRecords = getCustomerMonthRecords,
  loadBills = getCustomerBills,
  loadAccount = getCustomerAccount,
}: CustomerMonthProps) {
  const currentMonth = monthOf(today)
  // The calendar starts at the month the customer joined, or a year back if the join date is unknown.
  const firstMonth = joinDate && monthOf(joinDate) <= currentMonth ? monthOf(joinDate) : shiftMonth(currentMonth, -12)
  const [month, setMonth] = useState<Month>(currentMonth)
  const [records, setRecords] = useState<CustomerDailyRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedDate, setSelectedDate] = useState(today)
  const [latestBill, setLatestBill] = useState<CustomerBill | null | undefined>(undefined)
  const [account, setAccount] = useState<Account | null>(null)
  const requestId = useRef(0)

  const load = useCallback(async (target: Month) => {
    const request = ++requestId.current
    setLoading(true)
    setError('')
    try {
      const result = await loadRecords(token, target, today)
      if (request === requestId.current) setRecords(result)
    } catch (cause) {
      if (request !== requestId.current) return
      if (isUnauthenticatedError(cause)) onSessionExpired()
      else setError(dailyRecordErrorMessage(cause))
    } finally {
      if (request === requestId.current) setLoading(false)
    }
  }, [loadRecords, onSessionExpired, today, token])

  useEffect(() => {
    void load(month)
    return () => { requestId.current += 1 }
  }, [load, month])

  useEffect(() => {
    let active = true
    loadBills(token, 0, 1)
      .then((page) => { if (active) setLatestBill(page.content[0] ?? null) })
      .catch((cause: unknown) => {
        if (!active) return
        if (isUnauthenticatedError(cause)) onSessionExpired()
        else setLatestBill(null)
      })
    return () => { active = false }
  }, [loadBills, onSessionExpired, token])

  useEffect(() => {
    let active = true
    // The balance is extra information; without it the box falls back to the latest bill.
    loadAccount(token)
      .then((result) => { if (active) setAccount(result) })
      .catch((cause: unknown) => { if (active && isUnauthenticatedError(cause)) onSessionExpired() })
    return () => { active = false }
  }, [loadAccount, onSessionExpired, token])

  function goTo(target: Month) {
    setMonth(target)
    setRecords([])
    setSelectedDate(target === currentMonth ? today : `${target}-01`)
  }

  const summary = summarizeMonth(records)
  const isCurrent = month === currentMonth
  const cells = calendarCells(month, today, records)
  const selected = cells.find((cell) => cell.kind === 'day' && cell.date === selectedDate)
  const selectedRecords = selected && selected.kind === 'day' ? selected.records : []
  const amount = rupees(summary.amount)
  const bill = latestBill ? rupees(latestBill.totalAmount) : null
  const showCredit = account !== null && account.amountDue === 0 && account.credit > 0
  const balance = account ? rupees(showCredit ? account.credit : account.amountDue) : null
  const shortMonth = monthName(month).split(' ')[0]

  return (
    <section className="customer-month" aria-labelledby="month-title">
      <div className="section-heading">
        <div><p className="eyebrow">THIS MONTH AT A GLANCE</p><h2 id="month-title">{isCurrent ? `${shortMonth} so far` : `${shortMonth} at a glance`}</h2></div>
        <div className="month-nav" role="group" aria-label="Choose month">
          <button type="button" onClick={() => goTo(shiftMonth(month, -1))} disabled={month <= firstMonth} aria-label="Previous month">‹</button>
          <output aria-live="polite">{monthName(month)}</output>
          <button type="button" onClick={() => goTo(shiftMonth(month, 1))} disabled={month >= currentMonth} aria-label="Next month">›</button>
        </div>
      </div>

      <div className="month-strip" aria-busy={loading}>
        <div>
          <span className="eyebrow">Collected</span>
          <span className="month-big">{loading ? '—' : <>{quantity(summary.litres)}<small> L</small></>}</span>
          <span className="month-note">
            {loading ? 'Loading…' : `${summary.collections} collection${summary.collections === 1 ? '' : 's'}`}
            {!loading && summary.kilograms > 0 ? ` · plus ${quantity(summary.kilograms)} kg` : ''}
          </span>
        </div>
        <div>
          <span className="eyebrow">{isCurrent ? 'Amount so far' : 'Month total'}</span>
          <span className="month-big">{loading ? '—' : <>{amount.whole}<small>{amount.paise}</small></>}</span>
          <span className="month-note">{isCurrent ? 'Billed after the month ends' : 'From your recorded collections'}</span>
        </div>
        {account && balance ? (
          <div className={account.amountDue > 0 ? 'month-owed' : 'month-settled'}>
            <span className="eyebrow">{showCredit ? 'Credit on your account' : 'Balance due'}</span>
            <span className="month-big">{balance.whole}<small>{balance.paise}</small></span>
            <span className="month-note">
              {showCredit ? 'Used on your next bill' : account.amountDue > 0 ? 'From your issued bills' : latestBill ? 'All bills paid' : 'Nothing to pay'}
              {latestBill && <> · Latest: {monthName(latestBill.period)} {bill?.whole}{bill?.paise} · <button type="button" className="text-button" onClick={onOpenBills}>See bill</button></>}
            </span>
          </div>
        ) : (
          <div>
            <span className="eyebrow">Latest bill</span>
            <span className="month-big">{bill ? <>{bill.whole}<small>{bill.paise}</small></> : '—'}</span>
            <span className="month-note">
              {latestBill === undefined ? 'Loading…' : latestBill
                ? <>{monthName(latestBill.period)} · <button type="button" className="text-button" onClick={onOpenBills}>See bill</button></>
                : 'No bills issued yet'}
            </span>
          </div>
        )}
      </div>

      {error ? (
        <div className="customer-state customer-error">
          <p className="alert error-alert" role="alert">{error}</p>
          <button className="secondary-button" onClick={() => void load(month)}>Try again</button>
        </div>
      ) : (
        <div className="calendar-panel">
          <div className="calendar-grid" role="group" aria-label={`Collections in ${monthName(month)}`}>
            {WEEKDAYS.map((day) => <div className="calendar-weekday" key={day} aria-hidden="true">{day}</div>)}
            {cells.map((cell) => cell.kind === 'blank'
              ? <div className="calendar-day blank" key={cell.key} aria-hidden="true" />
              : (
                <button
                  key={cell.key}
                  type="button"
                  className={`calendar-day${cell.today ? ' is-today' : ''}`}
                  disabled={cell.future || loading}
                  aria-pressed={cell.date === selectedDate}
                  aria-label={`${dayName(cell.date)}: ${cell.records.length
                    ? cell.records.map((record) => `${record.slot === 'MORNING' ? 'morning' : 'evening'} ${dailyRecordStatusLabel(record.status).toLowerCase()}`).join(', ')
                    : 'no records'}`}
                  onClick={() => setSelectedDate(cell.date)}
                >
                  <span className="calendar-date">{cell.day}</span>
                  <span className="calendar-marks" aria-hidden="true">
                    {cell.records.map((record) => <i key={record.id} className={`calendar-mark ${record.status.toLowerCase()}`} />)}
                  </span>
                </button>
              ))}
          </div>
          <div className="calendar-legend" aria-hidden="true">
            <span><i className="calendar-mark collected" /> Collected</span>
            <span><i className="calendar-mark extra" /> Extra</span>
            <span><i className="calendar-mark skipped" /> Skipped</span>
            <span><i className="calendar-mark paused" /> Paused</span>
          </div>
          <div className="calendar-detail" aria-live="polite">
            <h3>{selected ? dayName(selectedDate) : 'Choose a day'}</h3>
            {loading ? <p className="muted">Loading…</p> : selectedRecords.length === 0 ? (
              <p className="muted">{selectedDate > today ? 'This day has not happened yet.' : 'No collection was recorded on this day.'}</p>
            ) : (
              <ul>
                {selectedRecords.map((record) => (
                  <li key={record.id}>
                    <span>{record.slot === 'MORNING' ? 'Morning' : 'Evening'} · {record.productName}</span>
                    <span className="calendar-detail-values">
                      {record.quantity > 0 && <span className="amount-cell">{quantity(record.quantity)} {unitLabel(record)} · ₹{record.amount.toFixed(2)}</span>}
                      <span className={`status-pill record-status ${record.status.toLowerCase()}`}>{dailyRecordStatusLabel(record.status)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </section>
  )
}

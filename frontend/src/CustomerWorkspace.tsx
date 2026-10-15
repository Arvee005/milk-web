import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { isUnauthenticatedError } from './authApi'
import {
  customerProfileErrorMessage,
  customerProfileViewState,
  getCustomerProfile,
  type CustomerProfile,
} from './customerApi'
import {
  DAILY_RECORD_PAGE_SIZE,
  dailyCollectionSlots,
  dailyRecordErrorMessage,
  dailyRecordStatusLabel,
  getCustomerDailyRecords,
  recordsViewState,
  validateDailyRecordFilters,
  type CustomerDailyRecord,
  type DailyRecordPage,
  type DailyRecordSlot,
  type DailyRecordStatus,
} from './dailyRecordsApi'
import {
  getCustomerSubscriptions,
  groupCustomerSubscriptions,
  subscriptionErrorMessage,
  subscriptionPeriodStateLabel,
  subscriptionStatusLabel,
  subscriptionsViewState,
  type CustomerSubscription,
} from './subscriptionsApi'
import {
  billErrorMessage,
  billStatusLabel,
  billsViewState,
  CUSTOMER_BILL_PAGE_SIZE,
  getCustomerBills,
  type CustomerBill,
  type CustomerBillPage,
} from './billsApi'

type CustomerSection = 'overview' | 'records' | 'subscriptions' | 'bills' | 'profile'

type CustomerWorkspaceProps = {
  token: string
  onSignOut: () => void
  onSessionExpired: () => void
}

const sections: { id: CustomerSection; label: string; icon: string }[] = [
  { id: 'overview', label: 'Overview', icon: '⌂' },
  { id: 'records', label: 'Records', icon: '◷' },
  { id: 'subscriptions', label: 'Subscriptions', icon: '♧' },
  { id: 'bills', label: 'Bills', icon: '▤' },
  { id: 'profile', label: 'Profile', icon: '○' },
]

function formatJoinDate(value: string) {
  const date = new Date(`${value}T00:00:00+05:30`)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('en-IN', {
    day: '2-digit',
    month: 'long',
    year: 'numeric',
    timeZone: 'Asia/Kolkata',
  }).format(date)
}

function todayInIndia() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const part = (type: string) => parts.find((value) => value.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

function daysBefore(date: string, days: number) {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() - days)
  return value.toISOString().slice(0, 10)
}

function formatRecordDate(value: string) {
  const date = new Date(`${value}T00:00:00+05:30`)
  return new Intl.DateTimeFormat('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'Asia/Kolkata',
  }).format(date)
}

function formatQuantity(value: number) {
  return new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 }).format(value)
}

function formatAmount(value: number) {
  return new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

function formatBillingPeriod(period: string) {
  const date = new Date(`${period}-01T00:00:00+05:30`)
  if (Number.isNaN(date.getTime())) return period
  return new Intl.DateTimeFormat('en-IN', {
    month: 'long',
    year: 'numeric',
    timeZone: 'Asia/Kolkata',
  }).format(date)
}

function formatIssueDate(value: string) {
  return new Intl.DateTimeFormat('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'Asia/Kolkata',
  }).format(new Date(value))
}

function CustomerBillCard({ bill }: { bill: CustomerBill }) {
  const [expanded, setExpanded] = useState(false)
  return (
    <article className="customer-bill-card">
      <div className="customer-bill-heading">
        <div>
          <p className="eyebrow">BILLING PERIOD</p>
          <h2>{formatBillingPeriod(bill.period)}</h2>
        </div>
        <span className="status-pill bill-status">{billStatusLabel(bill.status)}</span>
      </div>
      <dl className="customer-bill-summary">
        <div><dt>Issued</dt><dd>{formatIssueDate(bill.issuedAt)}</dd></div>
        <div><dt>Total amount</dt><dd>{formatAmount(bill.totalAmount)}</dd></div>
        <div><dt>Period dates</dt><dd>{formatRecordDate(bill.periodStart)} – {formatRecordDate(bill.periodEnd)}</dd></div>
      </dl>
      <button
        className="bill-details-toggle"
        type="button"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >
        {expanded ? 'Hide bill details' : `View bill details (${bill.lines.length} items)`}
      </button>
      {expanded && (
        <div className="customer-bill-lines">
          <h3>Bill items</h3>
          {bill.lines.map((line, index) => (
            <article className="customer-bill-line" key={`${line.recordDate}-${line.slot}-${index}`}>
              <div>
                <p>{line.description}</p>
                <span>{formatRecordDate(line.recordDate)} · {line.slot === 'MORNING' ? 'Morning' : 'Evening'}</span>
              </div>
              <dl>
                <div><dt>Quantity</dt><dd>{formatQuantity(line.quantity)}</dd></div>
                <div><dt>Unit price</dt><dd>{formatAmount(line.unitPrice)}</dd></div>
                <div><dt>Amount</dt><dd>{formatAmount(line.amount)}</dd></div>
              </dl>
            </article>
          ))}
        </div>
      )}
    </article>
  )
}

function CustomerSubscriptionCard({ subscription }: { subscription: CustomerSubscription }) {
  return (
    <article className="customer-subscription-card">
      <div className="customer-subscription-heading">
        <div>
          <h3>{subscription.productName}</h3>
          <p>{formatQuantity(subscription.defaultQuantity)} {subscription.productUnit === 'LITRE' ? 'L' : 'kg'} planned</p>
        </div>
        <span className={`status-pill subscription-status ${subscription.status.toLowerCase()}`}>
          {subscriptionStatusLabel(subscription.status)}
        </span>
      </div>
      <dl className="customer-subscription-details">
        <div><dt>Starts</dt><dd>{formatRecordDate(subscription.startDate)}</dd></div>
        <div><dt>Ends</dt><dd>{subscription.endDate ? formatRecordDate(subscription.endDate) : 'No end date'}</dd></div>
        <div className="subscription-period">
          <dt>Date range</dt><dd>{subscriptionPeriodStateLabel(subscription.periodState)}</dd>
        </div>
      </dl>
    </article>
  )
}

function RecordCard({ record }: { record: CustomerDailyRecord }) {
  const chargeable = record.status === 'COLLECTED' || record.status === 'EXTRA'
  return (
    <article className="daily-record-card">
      <div className="daily-record-main">
        <div>
          <p className="daily-record-product">{record.productName}</p>
          <p className="daily-record-quantity">
            {formatQuantity(record.quantity)} {record.productUnit === 'LITRE' ? 'L' : 'kg'}
          </p>
        </div>
        <span className={`status-pill record-status ${record.status.toLowerCase()}`}>
          {dailyRecordStatusLabel(record.status)}
        </span>
      </div>
      <dl className="daily-record-details">
        <div><dt>Date</dt><dd>{formatRecordDate(record.recordDate)}</dd></div>
        <div><dt>Slot</dt><dd>{record.slot === 'MORNING' ? 'Morning' : 'Evening'}</dd></div>
        {chargeable && (
          <>
            <div><dt>Unit price snapshot</dt><dd>{formatAmount(record.unitPriceSnapshot ?? 0)}</dd></div>
            <div><dt>Amount</dt><dd>{formatAmount(record.amount)}</dd></div>
          </>
        )}
        {!chargeable && <div><dt>Amount</dt><dd>Not chargeable</dd></div>}
      </dl>
    </article>
  )
}

function CustomerProfileView({
  profile,
  loading,
  error,
  onRetry,
}: {
  profile: CustomerProfile | null
  loading: boolean
  error: string
  onRetry: () => void
}) {
  const state = customerProfileViewState(profile, loading, error)
  if (state.status === 'loading') {
    return <div className="customer-state" role="status">Loading your profile…</div>
  }
  if (state.status === 'error') {
    return (
      <section className="customer-state customer-error">
        <h2>We could not load your profile</h2>
        <p className="alert error-alert" role="alert">{state.message}</p>
        <button className="secondary-button" onClick={onRetry}>Try again</button>
      </section>
    )
  }
  if (state.status === 'empty') {
    return <div className="customer-state" role="status">Your profile is not available.</div>
  }
  const currentProfile = state.profile

  return (
    <>
      <section className="customer-profile-card" aria-labelledby="profile-details-title">
        <div className="customer-profile-heading">
          <span className="customer-profile-avatar" aria-hidden="true">
            {currentProfile.name.trim().charAt(0).toUpperCase() || 'C'}
          </span>
          <div>
            <p className="eyebrow">YOUR ACCOUNT</p>
            <h2 id="profile-details-title">{currentProfile.name}</h2>
            <p className="customer-profile-caption">Customer profile</p>
          </div>
          <span className={`status-pill customer-status ${currentProfile.status.toLowerCase()}`}>
            {currentProfile.status === 'PENDING' ? 'PENDING APPROVAL' : currentProfile.status}
          </span>
        </div>
        {currentProfile.status === 'PENDING' && (
          <p className="alert pending-alert" role="status">
            Your account has been created and is waiting for the dairy owner to approve it. Your milk records,
            subscriptions, and bills will appear here once the owner sets up your account.
          </p>
        )}
        <dl className="customer-profile-grid">
          <div><dt>Phone number</dt><dd>{currentProfile.phone || 'Not provided'}</dd></div>
          <div><dt>Address</dt><dd>{currentProfile.address || 'Not provided'}</dd></div>
          <div><dt>Billing preference</dt><dd>{currentProfile.billingType === 'MONTHLY' ? 'Monthly' : 'Prepaid'}</dd></div>
          <div><dt>Customer since</dt><dd>{formatJoinDate(currentProfile.joinDate)}</dd></div>
        </dl>
      </section>
      <p className="customer-readonly-note">Need to update your details? Please contact the dairy owner.</p>
    </>
  )
}

export default function CustomerWorkspace({
  token,
  onSignOut,
  onSessionExpired,
}: CustomerWorkspaceProps) {
  const [activeSection, setActiveSection] = useState<CustomerSection>('overview')
  const [profile, setProfile] = useState<CustomerProfile | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const requestId = useRef(0)
  const today = todayInIndia()
  const [todayRecords, setTodayRecords] = useState<CustomerDailyRecord[]>([])
  const [todayRecordTotal, setTodayRecordTotal] = useState(0)
  const [todayLoading, setTodayLoading] = useState(true)
  const [todayError, setTodayError] = useState('')
  const [fromDate, setFromDate] = useState(() => daysBefore(today, 6))
  const [toDate, setToDate] = useState(today)
  const [appliedRange, setAppliedRange] = useState({ fromDate: daysBefore(today, 6), toDate: today })
  const [historySlot, setHistorySlot] = useState<'' | DailyRecordSlot>('')
  const [historyStatus, setHistoryStatus] = useState<'' | DailyRecordStatus>('')
  const [appliedSlot, setAppliedSlot] = useState<'' | DailyRecordSlot>('')
  const [appliedStatus, setAppliedStatus] = useState<'' | DailyRecordStatus>('')
  const [historyPage, setHistoryPage] = useState(0)
  const [history, setHistory] = useState<DailyRecordPage | null>(null)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState('')
  const [filterError, setFilterError] = useState('')
  const [subscriptions, setSubscriptions] = useState<CustomerSubscription[] | null>(null)
  const [subscriptionsLoading, setSubscriptionsLoading] = useState(false)
  const [subscriptionsError, setSubscriptionsError] = useState('')
  const [bills, setBills] = useState<CustomerBillPage | null>(null)
  const [billsPage, setBillsPage] = useState(0)
  const [billsLoading, setBillsLoading] = useState(false)
  const [billsError, setBillsError] = useState('')
  const todayRequestId = useRef(0)
  const historyRequestId = useRef(0)
  const subscriptionsRequestId = useRef(0)
  const billsRequestId = useRef(0)

  const loadProfile = useCallback(async () => {
    const currentRequest = ++requestId.current
    setLoading(true)
    setError('')
    try {
      const result = await getCustomerProfile(token)
      if (currentRequest === requestId.current) setProfile(result)
    } catch (cause) {
      if (currentRequest !== requestId.current) return
      if (isUnauthenticatedError(cause)) {
        onSessionExpired()
      } else {
        setError(customerProfileErrorMessage(cause))
      }
    } finally {
      if (currentRequest === requestId.current) setLoading(false)
    }
  }, [onSessionExpired, token])

  useEffect(() => {
    void loadProfile()
    return () => {
      requestId.current += 1
    }
  }, [loadProfile])

  const loadToday = useCallback(async () => {
    const request = ++todayRequestId.current
    setTodayLoading(true)
    setTodayError('')
    try {
      const result = await getCustomerDailyRecords(token, { recordDate: today, page: 0, size: 100 })
      if (request === todayRequestId.current) {
        setTodayRecords(result.content)
        setTodayRecordTotal(result.totalElements)
      }
    } catch (cause) {
      if (request !== todayRequestId.current) return
      if (isUnauthenticatedError(cause)) onSessionExpired()
      else setTodayError(dailyRecordErrorMessage(cause))
    } finally {
      if (request === todayRequestId.current) setTodayLoading(false)
    }
  }, [onSessionExpired, today, token])

  useEffect(() => {
    if (activeSection !== 'overview') return
    void loadToday()
    const refreshTimer = window.setInterval(() => void loadToday(), 60_000)
    return () => {
      window.clearInterval(refreshTimer)
      todayRequestId.current += 1
    }
  }, [activeSection, loadToday])

  const loadHistory = useCallback(async () => {
    const request = ++historyRequestId.current
    setHistoryLoading(true)
    setHistoryError('')
    setFilterError('')
    try {
      const result = await getCustomerDailyRecords(token, {
        ...appliedRange,
        ...(appliedSlot ? { slot: appliedSlot } : {}),
        ...(appliedStatus ? { status: appliedStatus } : {}),
        page: historyPage,
        size: DAILY_RECORD_PAGE_SIZE,
      })
      if (request === historyRequestId.current) setHistory(result)
    } catch (cause) {
      if (request !== historyRequestId.current) return
      if (isUnauthenticatedError(cause)) onSessionExpired()
      else setHistoryError(dailyRecordErrorMessage(cause))
    } finally {
      if (request === historyRequestId.current) setHistoryLoading(false)
    }
  }, [appliedRange, appliedSlot, appliedStatus, historyPage, onSessionExpired, token])

  useEffect(() => {
    if (activeSection !== 'records') return
    void loadHistory()
    return () => {
      historyRequestId.current += 1
    }
  }, [activeSection, loadHistory])

  const loadSubscriptions = useCallback(async () => {
    const request = ++subscriptionsRequestId.current
    setSubscriptionsLoading(true)
    setSubscriptionsError('')
    try {
      const result = await getCustomerSubscriptions(token)
      if (request === subscriptionsRequestId.current) setSubscriptions(result)
    } catch (cause) {
      if (request !== subscriptionsRequestId.current) return
      if (isUnauthenticatedError(cause)) onSessionExpired()
      else setSubscriptionsError(subscriptionErrorMessage(cause))
    } finally {
      if (request === subscriptionsRequestId.current) setSubscriptionsLoading(false)
    }
  }, [onSessionExpired, token])

  useEffect(() => {
    if (activeSection !== 'subscriptions') return
    void loadSubscriptions()
    return () => {
      subscriptionsRequestId.current += 1
    }
  }, [activeSection, loadSubscriptions])

  const loadBills = useCallback(async () => {
    const request = ++billsRequestId.current
    setBillsLoading(true)
    setBillsError('')
    try {
      const result = await getCustomerBills(token, billsPage, CUSTOMER_BILL_PAGE_SIZE)
      if (request === billsRequestId.current) setBills(result)
    } catch (cause) {
      if (request !== billsRequestId.current) return
      if (isUnauthenticatedError(cause)) onSessionExpired()
      else setBillsError(billErrorMessage(cause))
    } finally {
      if (request === billsRequestId.current) setBillsLoading(false)
    }
  }, [billsPage, onSessionExpired, token])

  useEffect(() => {
    if (activeSection !== 'bills') return
    void loadBills()
    return () => {
      billsRequestId.current += 1
    }
  }, [activeSection, loadBills])

  function applyHistoryFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const validationError = validateDailyRecordFilters({ fromDate, toDate })
    if (validationError) {
      setFilterError(validationError)
      return
    }
    setFilterError('')
    setHistoryPage(0)
    setAppliedRange({ fromDate, toDate })
    setAppliedSlot(historySlot)
    setAppliedStatus(historyStatus)
  }

  const title = activeSection === 'profile'
    ? 'Your profile'
    : sections.find((section) => section.id === activeSection)?.label ?? 'Overview'
  const todaySlots = dailyCollectionSlots(todayRecords)
  const historyState = recordsViewState(history?.content ?? [], historyLoading, historyError)
  const subscriptionsState = subscriptionsViewState(
    subscriptions, subscriptionsLoading, subscriptionsError,
  )
  const subscriptionGroups = subscriptionsState.status === 'ready'
    ? groupCustomerSubscriptions(subscriptionsState.subscriptions)
    : null
  const billsState = billsViewState(bills, billsLoading, billsError)

  return (
    <div className="app-shell customer-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Milk Web home">
          <span className="brand-mark">m</span><span>milk<span className="brand-light">web</span></span>
        </a>
        <div className="workspace-label">MY ACCOUNT</div>
        <nav aria-label="Customer navigation">
          {sections.map((section) => (
            <button
              key={section.id}
              type="button"
              className={`nav-item customer-nav-item${activeSection === section.id ? ' active' : ''}`}
              aria-current={activeSection === section.id ? 'page' : undefined}
              onClick={() => setActiveSection(section.id)}
            >
              <span className="nav-icon" aria-hidden="true">{section.icon}</span>
              {section.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="secure-note"><span className="secure-dot" /> Secure customer session</div>
          <button className="signout-button" onClick={onSignOut}>Sign out</button>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumb">My account <span>/</span> {title}</div>
          <div className="topbar-right">
            <span className="today-label">{new Intl.DateTimeFormat('en-IN', {
              dateStyle: 'medium', timeZone: 'Asia/Kolkata',
            }).format(new Date())}</span>
            <span className="avatar" aria-label="Customer account">CU</span>
          </div>
        </header>

        <div className="page-content customer-page-content">
          <div className="page-heading">
            <div>
              <p className="eyebrow">MILK WEB · CUSTOMER</p>
              <h1>{title}</h1>
              <p className="page-subtitle">
                {activeSection === 'overview'
                  ? 'A clear view of today’s recorded collections.'
                  : activeSection === 'records'
                    ? 'Review your recorded collections by date, slot, and status.'
                      : activeSection === 'subscriptions'
                        ? 'See your recurring milk plan separately from actual collection records.'
                        : activeSection === 'bills'
                          ? 'Review bills that have been issued for your account.'
                      : 'Your account information, all in one place.'}
              </p>
            </div>
          </div>

          {activeSection === 'overview' ? (
            <section className="today-collections" aria-labelledby="today-collections-title">
              <div className="section-heading">
                <div><p className="eyebrow">COLLECTIONS</p><h2 id="today-collections-title">Today <span className="today-date">{formatRecordDate(today)}</span></h2></div>
                <button className="secondary-button" onClick={() => void loadToday()} disabled={todayLoading}>
                  {todayLoading ? 'Refreshing…' : 'Refresh'}
                </button>
              </div>
              {todayError && (
                <section className="customer-state customer-error">
                  <p className="alert error-alert" role="alert">{todayError}</p>
                  <button className="secondary-button" onClick={() => void loadToday()}>Try again</button>
                </section>
              )}
              {!todayError && todayRecordTotal > todayRecords.length && (
                <p className="alert records-limit-notice" role="status">
                  Showing the first {todayRecords.length} records. Open Records to browse the full list.
                </p>
              )}
              {todayLoading ? (
                <div className="customer-state" role="status">Loading today’s collection records…</div>
              ) : !todayError ? (
                <div className="today-slot-grid">
                  {todaySlots.map(({ slot, records }) => (
                    <section className="today-slot-card" key={slot} aria-labelledby={`slot-${slot}`}>
                      <p className="eyebrow">{slot === 'MORNING' ? 'MORNING COLLECTION' : 'EVENING COLLECTION'}</p>
                      <h3 id={`slot-${slot}`}>{slot === 'MORNING' ? 'Morning' : 'Evening'}</h3>
                      {records.length > 0 ? records.map((record) => (
                        <RecordCard key={record.id} record={record} />
                      )) : <p className="missing-record">No record available for this slot.</p>}
                    </section>
                  ))}
                </div>
              ) : null}
            </section>
          ) : activeSection === 'records' ? (
            <section className="records-history" aria-labelledby="records-history-title">
              <form className="records-filter" onSubmit={applyHistoryFilters}>
                <label>From<input type="date" value={fromDate} max={today} onChange={(event) => setFromDate(event.target.value)} /></label>
                <label>To<input type="date" value={toDate} max={today} onChange={(event) => setToDate(event.target.value)} /></label>
                <label>Collection slot
                  <select value={historySlot} onChange={(event) => setHistorySlot(event.target.value as '' | DailyRecordSlot)}>
                    <option value="">All slots</option><option value="MORNING">Morning</option><option value="EVENING">Evening</option>
                  </select>
                </label>
                <label>Status
                  <select value={historyStatus} onChange={(event) => setHistoryStatus(event.target.value as '' | DailyRecordStatus)}>
                    <option value="">All statuses</option>
                    <option value="COLLECTED">Collected</option><option value="EXTRA">Extra milk</option>
                    <option value="SKIPPED">Skipped</option><option value="PAUSED">Paused</option>
                  </select>
                </label>
                <button className="primary-button" type="submit">Apply filters</button>
              </form>
              {filterError && <p className="alert error-alert" role="alert">{filterError}</p>}
              {historyState.status === 'loading' ? (
                <div className="customer-state" role="status">Loading collection records…</div>
              ) : historyState.status === 'error' ? (
                <section className="customer-state customer-error">
                  <p className="alert error-alert" role="alert">{historyState.message}</p>
                  <button className="secondary-button" onClick={() => void loadHistory()}>Try again</button>
                </section>
              ) : historyState.status === 'empty' ? (
                <div className="customer-state"><h2 id="records-history-title">No records found</h2><p>There are no recorded collections for these filters.</p></div>
              ) : (
                <>
                  <div className="records-list">
                    {historyState.records.map((record) => <RecordCard key={record.id} record={record} />)}
                  </div>
                  <div className="records-pagination">
                    <span>{history?.totalElements ?? 0} records</span>
                    <div>
                      <button className="secondary-button" disabled={!history || history.number <= 0 || historyLoading}
                        onClick={() => setHistoryPage((page) => Math.max(0, page - 1))}>Previous</button>
                      <span>Page {(history?.number ?? 0) + 1} of {Math.max(1, history?.totalPages ?? 1)}</span>
                      <button className="secondary-button" disabled={!history || history.number + 1 >= history.totalPages || historyLoading}
                        onClick={() => setHistoryPage((page) => page + 1)}>Next</button>
                    </div>
                  </div>
                </>
              )}
            </section>
          ) : activeSection === 'profile' ? (
            <CustomerProfileView
              profile={profile}
              loading={loading}
              error={error}
              onRetry={() => void loadProfile()}
            />
          ) : activeSection === 'subscriptions' ? (
            <section className="customer-subscriptions" aria-labelledby="subscriptions-title">
              <p className="customer-readonly-note">
                Default quantities are planned amounts, not proof of milk collected. Check Records for actual collection.
              </p>
              {subscriptionsState.status === 'loading' ? (
                <div className="customer-state" role="status">Loading your subscriptions…</div>
              ) : subscriptionsState.status === 'error' ? (
                <section className="customer-state customer-error">
                  <h2>We could not load your subscriptions</h2>
                  <p className="alert error-alert" role="alert">{subscriptionsState.message}</p>
                  <button className="secondary-button" onClick={() => void loadSubscriptions()}>Try again</button>
                </section>
              ) : subscriptionsState.status === 'empty' ? (
                <section className="customer-state">
                  <h2 id="subscriptions-title">No subscriptions found</h2>
                  <p>Your subscription details will appear here when they are available.</p>
                </section>
              ) : (
                <>
                  {subscriptionGroups?.effective.length ? (
                    <section className="subscription-group" aria-labelledby="effective-subscriptions-title">
                      <h2 id="effective-subscriptions-title">Effective today</h2>
                      <div className="customer-subscription-list">
                        {subscriptionGroups.effective.map((subscription) => (
                          <CustomerSubscriptionCard key={subscription.id} subscription={subscription} />
                        ))}
                      </div>
                    </section>
                  ) : null}
                  {subscriptionGroups?.other.length ? (
                    <section className="subscription-group" aria-labelledby="other-subscriptions-title">
                      <h2 id="other-subscriptions-title">Other subscriptions</h2>
                      <div className="customer-subscription-list">
                        {subscriptionGroups.other.map((subscription) => (
                          <CustomerSubscriptionCard key={subscription.id} subscription={subscription} />
                        ))}
                      </div>
                    </section>
                  ) : null}
                </>
              )}
            </section>
          ) : activeSection === 'bills' ? (
            <section className="customer-bills" aria-labelledby="customer-bills-title">
              <h2 className="sr-only" id="customer-bills-title">Issued bills</h2>
              <p className="customer-readonly-note">
                An issued bill is not a payment receipt. Payment status and currency are not recorded here.
              </p>
              {billsState.status === 'loading' ? (
                <div className="customer-state" role="status">Loading your issued bills…</div>
              ) : billsState.status === 'error' ? (
                <section className="customer-state customer-error">
                  <h2>We could not load your bills</h2>
                  <p className="alert error-alert" role="alert">{billsState.message}</p>
                  <button className="secondary-button" onClick={() => void loadBills()}>Try again</button>
                </section>
              ) : billsState.status === 'empty' ? (
                <section className="customer-state">
                  <h2>No issued bills yet</h2>
                  <p>Bills will appear here after they have been finalized and issued by the dairy owner.</p>
                </section>
              ) : (
                <>
                  <div className="customer-bill-list">
                    {billsState.page.content.map((bill) => (
                      <CustomerBillCard key={bill.id} bill={bill} />
                    ))}
                  </div>
                  <div className="records-pagination">
                    <span>{billsState.page.totalElements} issued bills</span>
                    <div>
                      <button
                        className="secondary-button"
                        disabled={billsState.page.number <= 0 || billsLoading}
                        onClick={() => setBillsPage((page) => Math.max(0, page - 1))}
                      >Previous</button>
                      <span>Page {billsState.page.number + 1} of {Math.max(1, billsState.page.totalPages)}</span>
                      <button
                        className="secondary-button"
                        disabled={billsState.page.number + 1 >= billsState.page.totalPages || billsLoading}
                        onClick={() => setBillsPage((page) => page + 1)}
                      >Next</button>
                    </div>
                  </div>
                </>
              )}
            </section>
          ) : (
            <section className="customer-placeholder" aria-labelledby="customer-placeholder-title">
              <span className="customer-placeholder-icon" aria-hidden="true">
                {sections.find((section) => section.id === activeSection)?.icon}
              </span>
              <p className="eyebrow">COMING IN A FUTURE UPDATE</p>
              <h2 id="customer-placeholder-title">Customer section coming soon</h2>
              <p>This section is not available yet.</p>
            </section>
          )}

          <footer className="page-footer">
            <span>Milk Web <b>·</b> Customer account</span>
            <span>Questions? Contact your dairy owner.</span>
          </footer>
        </div>
      </main>
    </div>
  )
}

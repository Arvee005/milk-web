import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  apiRequest,
  clearSessionToken,
  getCurrentUser,
  isUnauthenticatedError,
  login,
  readSessionToken,
  storeSessionToken,
  type AuthenticatedUser,
  workspaceForRole,
} from './authApi'
import CustomerWorkspace from './CustomerWorkspace'
import OwnerDailyRecords from './OwnerDailyRecords'
import './App.css'

type Customer = {
  id: string
  name: string
  phone: string | null
  billingType: 'MONTHLY' | 'PREPAID'
  status: 'ACTIVE' | 'PAUSED' | 'INACTIVE'
}

type BillingPeriod = {
  period: string
  periodStart: string
  periodEnd: string
  status: 'OPEN' | 'CLOSED'
  chargeableRecordCount: number
  chargeableAmount: number
}

type BillLine = {
  id: string
  dailyRecordId: string
  recordDate: string
  slot: string
  description: string
  quantity: number
  unitPrice: number
  amount: number
}

type Bill = {
  id: string
  customerId: string
  customerName: string
  customerPhone: string | null
  customerAddress: string | null
  period: string
  periodStart: string
  periodEnd: string
  totalAmount: number
  status: 'DRAFT' | 'ISSUED'
  createdAt: string
  issuedAt: string | null
  issuedByUserId: string | null
  lines: BillLine[]
}

type Page<T> = {
  content: T[]
  totalPages: number
  totalElements: number
  number: number
}

const PAGE_SIZE = 10
type OwnerSection = 'billing' | 'daily-records'

type Session =
  | { status: 'checking'; token: string; user: null }
  | { status: 'authenticated'; token: string; user: AuthenticatedUser }
  | { status: 'anonymous'; token: ''; user: null }
  | { status: 'error'; token: string; user: null }

function initialSession(): Session {
  const token = readSessionToken()
  return token
    ? { status: 'checking', token, user: null }
    : { status: 'anonymous', token: '', user: null }
}

function previousMonthInIndia() {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(new Date())
  const year = Number(parts.find((part) => part.type === 'year')?.value)
  const month = Number(parts.find((part) => part.type === 'month')?.value)
  const previous = new Date(Date.UTC(year, month - 2, 1))
  return `${previous.getUTCFullYear()}-${String(previous.getUTCMonth() + 1).padStart(2, '0')}`
}

function formatAmount(amount: number) {
  return new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount)
}

function formatDate(date: string | null) {
  if (!date) return '—'
  return new Intl.DateTimeFormat('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'Asia/Kolkata',
  }).format(new Date(`${date}T00:00:00+05:30`))
}

function formatMonth(period: string) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(period)) return 'Choose a month'
  const [year, month] = period.split('-').map(Number)
  return new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(Date.UTC(year, month - 1, 1)))
}

function App() {
  const [session, setSession] = useState<Session>(initialSession)
  const [ownerSection, setOwnerSection] = useState<OwnerSection>('billing')
  const token = session.token
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [loginLoading, setLoginLoading] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [periodId, setPeriodId] = useState(previousMonthInIndia)
  const [period, setPeriod] = useState<BillingPeriod | null>(null)
  const [customers, setCustomers] = useState<Customer[]>([])
  const [bills, setBills] = useState<Page<Bill> | null>(null)
  const [customerId, setCustomerId] = useState('')
  const [page, setPage] = useState(0)
  const [selectedBill, setSelectedBill] = useState<Bill | null>(null)
  const [busyAction, setBusyAction] = useState('')
  const workspaceRequestId = useRef(0)

  const clearSession = useCallback(() => {
    clearSessionToken()
    setSession({ status: 'anonymous', token: '', user: null })
    workspaceRequestId.current += 1
    setLoading(false)
    setPeriod(null)
    setCustomers([])
    setBills(null)
    setCustomerId('')
    setPage(0)
    setSelectedBill(null)
    setBusyAction('')
    setNotice('')
    setOwnerSection('billing')
  }, [])

  const reportRequestError = useCallback((cause: unknown, fallback: string) => {
    const message = cause instanceof Error ? cause.message : fallback
    if (isUnauthenticatedError(cause)) clearSession()
    setError(message)
  }, [clearSession])

  const loadWorkspace = useCallback(async (authToken: string, selectedPeriod: string, selectedPage: number) => {
    const requestId = ++workspaceRequestId.current
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(selectedPeriod)) {
      setPeriod(null)
      setBills(null)
      setLoading(false)
      setError('Choose a valid calendar month.')
      return
    }
    setLoading(true)
    setError('')
    try {
      const [periodResponse, customerResponse, billResponse] = await Promise.all([
        apiRequest<BillingPeriod>(`/owner/billing-periods/${selectedPeriod}`, authToken),
        apiRequest<Customer[]>('/owner/customers', authToken),
        apiRequest<Page<Bill>>(`/owner/bills?period=${selectedPeriod}&page=${selectedPage}&size=${PAGE_SIZE}`, authToken),
      ])
      if (requestId !== workspaceRequestId.current) return
      setPeriod(periodResponse)
      setCustomers(customerResponse)
      setBills(billResponse)
      setSelectedBill((current) => current
        ? billResponse.content.find((bill) => bill.id === current.id) ?? null
        : null)
    } catch (cause) {
      if (requestId !== workspaceRequestId.current) return
      reportRequestError(cause, 'Could not load billing data')
    } finally {
      if (requestId === workspaceRequestId.current) setLoading(false)
    }
  }, [reportRequestError])

  useEffect(() => {
    if (!session.token || session.status !== 'checking') return
    let active = true
    void getCurrentUser(session.token)
      .then((user) => {
        if (!active) return
        setError('')
        setSession({ status: 'authenticated', token: session.token, user })
      })
      .catch((cause: unknown) => {
        if (!active) return
        if (isUnauthenticatedError(cause)) {
          clearSessionToken()
          setSession({ status: 'anonymous', token: '', user: null })
          setError(cause.message)
          return
        }
        setSession({ status: 'error', token: session.token, user: null })
        setError(cause instanceof Error ? cause.message : 'Could not validate your session.')
      })
    return () => {
      active = false
    }
  }, [session.token, session.status])

  useEffect(() => {
    if (session.status !== 'authenticated' || session.user.role !== 'OWNER' || ownerSection !== 'billing') return
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(periodId)) {
      setPeriod(null)
      setBills(null)
      setError('Choose a valid calendar month.')
      return
    }
    void loadWorkspace(session.token, periodId, page)
  }, [session.status, session.token, session.user?.role, ownerSection, periodId, page, loadWorkspace])

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLoginLoading(true)
    setError('')
    try {
      const result = await login(phone.trim(), password)
      storeSessionToken(result.token)
      setPassword('')
      setSession({ status: 'checking', token: result.token, user: null })
    } catch (cause) {
      setError(cause instanceof TypeError
        ? 'Unable to connect to Milk Web. Check your connection and try again.'
        : cause instanceof Error ? cause.message : 'Sign in failed.')
    } finally {
      setLoginLoading(false)
    }
  }

  const expireSession = useCallback(() => {
    clearSession()
    setError('Your session has expired. Please sign in again.')
  }, [clearSession])

  function signOut() {
    clearSession()
    setError('')
  }

  async function closePeriod() {
    if (!token || !period) return
    setBusyAction('close')
    setError('')
    setNotice('')
    try {
      await apiRequest<BillingPeriod>(`/owner/billing-periods/${periodId}/close`, token, { method: 'POST' })
      setNotice(`${formatMonth(periodId)} has been closed. Daily records can no longer be changed.`)
      await loadWorkspace(token, periodId, page)
    } catch (cause) {
      reportRequestError(cause, 'Could not close the period')
    } finally {
      setBusyAction('')
    }
  }

  async function generateBill(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!token || !customerId) return
    setBusyAction('generate')
    setError('')
    setNotice('')
    try {
      const result = await apiRequest<Bill>(
        `/owner/billing-periods/${periodId}/bills/${customerId}`,
        token,
        { method: 'POST' },
      )
      setNotice(`Draft bill ${result.id.slice(0, 8)} is ready for ${result.customerName}.`)
      setSelectedBill(result)
      setPage(0)
      await loadWorkspace(token, periodId, 0)
    } catch (cause) {
      reportRequestError(cause, 'Could not generate the bill')
    } finally {
      setBusyAction('')
    }
  }

  async function issueBill(bill: Bill) {
    if (!token) return
    setBusyAction(bill.id)
    setError('')
    setNotice('')
    try {
      const issued = await apiRequest<Bill>(`/owner/bills/${bill.id}/issue`, token, { method: 'POST' })
      setSelectedBill(issued)
      setNotice(`Bill for ${issued.customerName} has been issued.`)
      await loadWorkspace(token, periodId, page)
    } catch (cause) {
      reportRequestError(cause, 'Could not issue the bill')
    } finally {
      setBusyAction('')
    }
  }

  if (session.status === 'checking') {
    return <main className="session-status" role="status">Checking your session…</main>
  }

  if (session.status === 'error') {
    return (
      <main className="session-status">
        <section className="session-error">
          <p className="eyebrow">SESSION CHECK</p>
          <h1>We could not verify your session.</h1>
          <p className="alert error-alert" role="alert">{error}</p>
          <button className="secondary-button" onClick={() => {
            setError('')
            setSession({ status: 'checking', token: session.token, user: null })
          }}>Retry session check</button>
          <button className="signout-button" onClick={signOut}>Sign out</button>
        </section>
      </main>
    )
  }

  if (session.status === 'anonymous') {
    return (
      <main className="login-page">
        <section className="login-card">
          <div className="brand-mark" aria-hidden="true">m</div>
          <p className="eyebrow">MILK WEB · SECURE ACCOUNT</p>
          <h1>Your dairy account, in one place.</h1>
          <p className="login-copy">Sign in with your phone number and password. We’ll take you to the right workspace.</p>
          <form onSubmit={signIn} className="login-form">
            <label htmlFor="account-phone">Phone number</label>
            <input id="account-phone" type="tel" inputMode="numeric" autoComplete="username"
              pattern="[0-9]{10}" maxLength={10} value={phone}
              onChange={(event) => setPhone(event.target.value.replace(/\D/g, '').slice(0, 10))}
              aria-describedby="phone-help" required />
            <span id="phone-help" className="form-hint">Enter the 10-digit phone number on your account.</span>
            <label htmlFor="account-password">Password</label>
            <input id="account-password" type="password" autoComplete="current-password" value={password}
              onChange={(event) => setPassword(event.target.value)} required />
            {error && <p className="alert error-alert" role="alert">{error}</p>}
            <button className="primary-button full-button" disabled={loginLoading} aria-busy={loginLoading}>
              {loginLoading ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
          <p className="privacy-note">Your session is kept only in this browser tab.</p>
        </section>
        <div className="login-art">
          <span className="art-sun" />
          <span className="art-hill hill-one" />
          <span className="art-hill hill-two" />
          <div className="art-caption"><span>01</span> A simpler way to keep every litre accounted for.</div>
        </div>
      </main>
    )
  }

  if (workspaceForRole(session.user.role) === 'customer') {
    return (
      <CustomerWorkspace
        token={session.token}
        onSignOut={signOut}
        onSessionExpired={expireSession}
      />
    )
  }

  if (ownerSection === 'daily-records') {
    return (
      <OwnerDailyRecords
        token={session.token}
        onSignOut={signOut}
        onSessionExpired={expireSession}
        onShowBilling={() => setOwnerSection('billing')}
      />
    )
  }

  const monthlyCustomers = customers.filter((customer) => customer.billingType === 'MONTHLY')
  const selectedCustomer = monthlyCustomers.find((customer) => customer.id === customerId)
  const canClose = Boolean(period && period.status === 'OPEN' && period.period < previousMonthInIndia())

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Milk Web home">
          <span className="brand-mark">m</span><span>milk<span className="brand-light">web</span></span>
        </a>
        <div className="workspace-label">WORKSPACE</div>
        <nav aria-label="Main navigation">
          <button type="button" className="nav-item owner-nav-item active" aria-current="page" onClick={() => setOwnerSection('billing')}><span className="nav-icon" aria-hidden="true">▤</span> Billing</button>
          <button type="button" className="nav-item owner-nav-item" onClick={() => {
            workspaceRequestId.current += 1
            setLoading(false)
            setOwnerSection('daily-records')
          }}><span className="nav-icon" aria-hidden="true">◷</span> Daily records</button>
          <span className="nav-item disabled"><span className="nav-icon">♧</span> Customers</span>
        </nav>
        <div className="sidebar-bottom">
          <div className="secure-note"><span className="secure-dot" /> Secure owner session</div>
          <button className="signout-button" onClick={signOut}>Sign out</button>
        </div>
      </aside>

      <main id="billing" className="main-content">
        <header className="topbar">
          <div className="breadcrumb">Workspace <span>/</span> Billing</div>
          <div className="topbar-right"><span className="today-label">{new Intl.DateTimeFormat('en-IN', {
            dateStyle: 'medium', timeZone: 'Asia/Kolkata',
          }).format(new Date())}</span><span className="avatar">OW</span></div>
        </header>

        <div className="page-content">
          <div className="page-heading">
            <div>
              <p className="eyebrow">ACCOUNTS &amp; RECONCILIATION</p>
              <h1>Billing</h1>
              <p className="page-subtitle">Review actual collections, close the month, and prepare customer bills.</p>
            </div>
            <label className="period-picker">
              <span>Billing month</span>
              <input type="month" value={periodId} onChange={(event) => {
                workspaceRequestId.current += 1
                setLoading(false)
                setPeriodId(event.target.value)
                setPage(0)
                setPeriod(null)
                setBills(null)
                setSelectedBill(null)
                setCustomerId('')
                setNotice('')
              }} disabled={busyAction !== ''} />
            </label>
          </div>

          {error && <div className="alert error-alert" role="alert">{error}</div>}
          {notice && <div className="alert success-alert" role="status">{notice}</div>}

          <section className="summary-grid" aria-label="Billing period summary">
            <article className="summary-card period-summary">
              <div className="card-topline"><span className="card-icon lavender">◷</span><span className={`status-pill ${period?.status === 'CLOSED' ? 'closed' : 'open'}`}>{period?.status ?? '—'}</span></div>
              <p className="summary-label">RECONCILIATION PERIOD</p>
              <h2>{formatMonth(periodId)}</h2>
              <p className="summary-foot">{period ? `${formatDate(period.periodStart)} — ${formatDate(period.periodEnd)}` : 'Loading period…'}</p>
            </article>
            <article className="summary-card">
              <div className="card-topline"><span className="card-icon mint">↗</span><span className="quiet-label">COLLECTED + EXTRA</span></div>
              <p className="summary-label">CHARGEABLE RECORDS</p>
              <h2>{period?.chargeableRecordCount ?? '—'} <span className="summary-unit">records</span></h2>
              <p className="summary-foot">Informational reconciliation total</p>
            </article>
            <article className="summary-card total-card">
              <div className="card-topline"><span className="card-icon peach">∑</span><span className="quiet-label">BILL TOTAL</span></div>
              <p className="summary-label">RECONCILED AMOUNT</p>
              <h2>{period ? formatAmount(period.chargeableAmount) : '—'}</h2>
              <p className="summary-foot">Currency awaits business confirmation</p>
            </article>
          </section>

          <section className="workflow-card">
            <div className="workflow-intro">
              <div className="step-badge">01</div>
              <div><p className="eyebrow">MONTH END</p><h2>Reconcile &amp; close</h2>
                <p>Confirm the records for this month before closing. Closing prevents further edits and backdated entries.</p></div>
            </div>
            <div className="workflow-action">
              {period?.status === 'CLOSED'
                ? <span className="complete-label"><span>✓</span> Period closed</span>
                : <button className="secondary-button" onClick={() => void closePeriod()}
                    disabled={!canClose || loading || busyAction !== ''}>
                    {busyAction === 'close' ? 'Closing…' : 'Close billing period'}
                  </button>}
              {period?.status === 'OPEN' && !canClose && <span className="action-hint">Only a completed month can be closed.</span>}
            </div>
          </section>

          <section className="workflow-card generate-card">
            <div className="workflow-intro">
              <div className="step-badge step-two">02</div>
              <div><p className="eyebrow">CUSTOMER STATEMENT</p><h2>Generate a draft bill</h2>
                <p>Monthly customers only. Bills use actual COLLECTED and EXTRA records, never subscription defaults.</p></div>
            </div>
            <form className="generate-form" onSubmit={generateBill}>
              <label htmlFor="bill-customer">Monthly customer</label>
              <select id="bill-customer" value={customerId} onChange={(event) => setCustomerId(event.target.value)}
                disabled={period?.status !== 'CLOSED' || loading}>
                <option value="">Select a customer</option>
                {monthlyCustomers.map((customer) => (
                  <option key={customer.id} value={customer.id}>{customer.name} · {customer.phone ?? 'No phone'}{customer.status !== 'ACTIVE' ? ` · ${customer.status.toLowerCase()}` : ''}</option>
                ))}
              </select>
              <button className="primary-button" disabled={period?.status !== 'CLOSED' || !customerId || loading || busyAction !== ''}>
                {busyAction === 'generate' ? 'Preparing…' : 'Generate draft'}
                <span aria-hidden="true"> ↗</span>
              </button>
              {period?.status !== 'CLOSED' && <p className="action-hint form-hint">Close this period before generating bills.</p>}
              {period?.status === 'CLOSED' && monthlyCustomers.length === 0 && <p className="action-hint form-hint">No monthly customers found.</p>}
              {selectedCustomer && <p className="action-hint form-hint">A nonzero bill is required. Existing bills are returned on retry.</p>}
            </form>
          </section>

          <section className="bills-section">
            <div className="section-heading">
              <div><p className="eyebrow">CUSTOMER ACCOUNTS</p><h2>Monthly bills <span className="count-badge">{bills?.totalElements ?? 0}</span></h2></div>
              <button className="text-button" onClick={() => void loadWorkspace(token, periodId, page)} disabled={loading}>
                {loading ? 'Refreshing…' : '↻ Refresh'}
              </button>
            </div>
            <div className="table-wrap">
              <table>
                <thead><tr><th>CUSTOMER</th><th>PERIOD</th><th>RECORDS</th><th>TOTAL</th><th>STATUS</th><th><span className="sr-only">Actions</span></th></tr></thead>
                <tbody>
                  {bills?.content.map((bill) => (
                    <tr key={bill.id} className={selectedBill?.id === bill.id ? 'selected-row' : ''}>
                      <td><button className="customer-cell" onClick={() => setSelectedBill(selectedBill?.id === bill.id ? null : bill)}>
                        <span className="customer-avatar">{bill.customerName.trim().charAt(0).toUpperCase()}</span>
                        <span><strong>{bill.customerName}</strong><small>{bill.customerPhone ?? bill.customerId.slice(0, 8)}</small></span>
                      </button></td>
                      <td>{formatMonth(bill.period)}</td>
                      <td>{bill.lines.length} <span className="muted">items</span></td>
                      <td className="amount-cell">{formatAmount(bill.totalAmount)}</td>
                      <td><span className={`status-pill ${bill.status.toLowerCase()}`}>{bill.status}</span></td>
                      <td className="row-action">{bill.status === 'DRAFT' && <button className="issue-button"
                        onClick={() => void issueBill(bill)} disabled={busyAction !== ''}>
                        {busyAction === bill.id ? 'Issuing…' : 'Issue'}
                      </button>}</td>
                    </tr>
                  ))}
                  {!loading && bills?.content.length === 0 && <tr><td colSpan={6} className="empty-state">
                    <span className="empty-icon">▤</span><strong>No bills for this month yet</strong>
                    <span>{period?.status === 'CLOSED'
                      ? 'Generate a draft for a monthly customer with chargeable records.'
                      : 'Close the period before generating bills.'}</span>
                  </td></tr>}
                  {loading && !bills && <tr><td colSpan={6} className="empty-state">Loading billing records…</td></tr>}
                </tbody>
              </table>
            </div>
            {selectedBill && <BillDetail bill={selectedBill} onClose={() => setSelectedBill(null)} />}
            <div className="table-footer">
              <span>{bills ? `${bills.totalElements} bill${bills.totalElements === 1 ? '' : 's'} · page ${bills.totalPages === 0 ? 0 : bills.number + 1} of ${bills.totalPages}` : '—'}</span>
              <div className="pagination">
                <button onClick={() => setPage((current) => Math.max(0, current - 1))}
                  disabled={!bills || page === 0 || loading}>Previous</button>
                <button onClick={() => setPage((current) => current + 1)}
                  disabled={!bills || page + 1 >= bills.totalPages || loading}>Next</button>
              </div>
            </div>
          </section>
          <footer className="page-footer"><span>Milk Web <b>·</b> Owner billing</span><span>All times and business dates follow Asia/Kolkata.</span></footer>
        </div>
      </main>
    </div>
  )
}

function BillDetail({ bill, onClose }: { bill: Bill; onClose: () => void }) {
  return (
    <section className="bill-detail" aria-label={`Bill details for ${bill.customerName}`}>
      <div className="detail-heading">
        <div><p className="eyebrow">BILL SNAPSHOT</p><h3>{bill.customerName}</h3>
          <p>{bill.customerPhone ?? 'No phone'}{bill.customerAddress ? ` · ${bill.customerAddress}` : ''}</p></div>
        <button className="close-detail" onClick={onClose} aria-label="Close bill details">×</button>
      </div>
      <div className="line-table-wrap">
        <table className="line-table">
          <thead><tr><th>DATE</th><th>SLOT</th><th>DESCRIPTION</th><th>QUANTITY</th><th>UNIT PRICE</th><th>AMOUNT</th></tr></thead>
          <tbody>{bill.lines.map((line) => <tr key={line.id}>
            <td>{formatDate(line.recordDate)}</td><td>{line.slot}</td><td>{line.description}</td>
            <td>{line.quantity}</td><td>{formatAmount(line.unitPrice)}</td><td>{formatAmount(line.amount)}</td>
          </tr>)}</tbody>
          <tfoot><tr><td colSpan={5}>Bill total</td><td>{formatAmount(bill.totalAmount)}</td></tr></tfoot>
        </table>
      </div>
      <p className="snapshot-note">Values are preserved from the bill snapshot and are not recalculated from current product prices.</p>
    </section>
  )
}

export default App

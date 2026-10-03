import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import './App.css'

type LoginResponse = {
  token: string
  userId: string
  role: 'OWNER' | 'CUSTOMER'
  phone: string
}

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

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api'
const TOKEN_KEY = 'milk-web-owner-token'
const PAGE_SIZE = 10

class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
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

function errorMessage(body: unknown, fallback: string) {
  if (typeof body === 'string' && body.trim() && !body.trimStart().startsWith('<')) {
    return body
  }
  if (typeof body === 'object' && body !== null && 'error' in body) {
    const message = body.error
    if (typeof message === 'string') return message
  }
  if (typeof body === 'object' && body !== null && 'detail' in body) {
    const detail = body.detail
    if (typeof detail === 'string') return detail
  }
  return fallback
}

async function readResponseBody(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}

function isLoginResponse(body: unknown): body is LoginResponse {
  return typeof body === 'object' && body !== null
    && 'token' in body && typeof body.token === 'string'
    && 'userId' in body && typeof body.userId === 'string'
    && 'phone' in body && typeof body.phone === 'string'
    && 'role' in body && (body.role === 'OWNER' || body.role === 'CUSTOMER')
}

async function request<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })
  const body: unknown = response.status === 204 ? null : await readResponseBody(response)
  if (!response.ok) {
    throw new ApiError(errorMessage(body, `Request failed (${response.status})`), response.status)
  }
  if (response.status !== 204 && (body === null || typeof body === 'string')) {
    throw new ApiError('The server returned an invalid response.', response.status)
  }
  return body as T
}

function App() {
  const [token, setToken] = useState(() => sessionStorage.getItem(TOKEN_KEY) ?? '')
  const [ownerPhone, setOwnerPhone] = useState('')
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
    sessionStorage.removeItem(TOKEN_KEY)
    setToken('')
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
  }, [])

  const reportRequestError = useCallback((cause: unknown, fallback: string) => {
    const message = cause instanceof Error ? cause.message : fallback
    if (cause instanceof ApiError && cause.status === 401) clearSession()
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
        request<BillingPeriod>(`/owner/billing-periods/${selectedPeriod}`, authToken),
        request<Customer[]>('/owner/customers', authToken),
        request<Page<Bill>>(`/owner/bills?period=${selectedPeriod}&page=${selectedPage}&size=${PAGE_SIZE}`, authToken),
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
    if (!token) return
    let active = true
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(periodId)) {
      setPeriod(null)
      setBills(null)
      setError('Choose a valid calendar month.')
      return () => {
        active = false
      }
    }
    void request<{ role: string }>('/auth/me', token)
      .then((user) => {
        if (!active) return
        if (user.role !== 'OWNER') {
          clearSession()
          throw new Error('This workspace is available to owner accounts only')
        }
        void loadWorkspace(token, periodId, page)
      })
      .catch((cause: unknown) => {
        if (!active) return
        reportRequestError(cause, 'Could not validate your session')
      })
    return () => {
      active = false
    }
  }, [token, periodId, page, loadWorkspace, clearSession, reportRequestError])

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLoginLoading(true)
    setError('')
    try {
      const response = await fetch(`${API_BASE}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone: ownerPhone, password }),
      })
      const body = await readResponseBody(response)
      if (!response.ok) throw new ApiError(errorMessage(body, 'Sign in failed'), response.status)
      if (!isLoginResponse(body)) {
        throw new Error('The server returned an invalid login response.')
      }
      const result = body
      if (result.role !== 'OWNER') throw new Error('This workspace is available to owner accounts only')
      sessionStorage.setItem(TOKEN_KEY, result.token)
      setPassword('')
      setToken(result.token)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Sign in failed')
    } finally {
      setLoginLoading(false)
    }
  }

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
      await request<BillingPeriod>(`/owner/billing-periods/${periodId}/close`, token, { method: 'POST' })
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
      const result = await request<Bill>(
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
      const issued = await request<Bill>(`/owner/bills/${bill.id}/issue`, token, { method: 'POST' })
      setSelectedBill(issued)
      setNotice(`Bill for ${issued.customerName} has been issued.`)
      await loadWorkspace(token, periodId, page)
    } catch (cause) {
      reportRequestError(cause, 'Could not issue the bill')
    } finally {
      setBusyAction('')
    }
  }

  if (!token) {
    return (
      <main className="login-page">
        <section className="login-card">
          <div className="brand-mark" aria-hidden="true">m</div>
          <p className="eyebrow">MILK WEB · OWNER PORTAL</p>
          <h1>Good business starts with a clear account.</h1>
          <p className="login-copy">Sign in to reconcile a month, prepare customer bills, and issue finalized statements.</p>
          <form onSubmit={signIn} className="login-form">
            <label htmlFor="owner-phone">Phone number</label>
            <input id="owner-phone" autoComplete="username" value={ownerPhone}
              onChange={(event) => setOwnerPhone(event.target.value)} required />
            <label htmlFor="owner-password">Password</label>
            <input id="owner-password" type="password" autoComplete="current-password" value={password}
              onChange={(event) => setPassword(event.target.value)} required />
            {error && <p className="alert error-alert" role="alert">{error}</p>}
            <button className="primary-button full-button" disabled={loginLoading}>
              {loginLoading ? 'Signing in…' : 'Sign in as owner'}
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
          <a href="#billing" className="nav-item active"><span className="nav-icon">▤</span> Billing</a>
          <span className="nav-item disabled"><span className="nav-icon">◷</span> Daily records</span>
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

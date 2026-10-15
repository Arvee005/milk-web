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
  type LoginResponse,
  workspaceForRole,
} from './authApi'
import BillReceipt from './BillReceipt'
import CustomerRegistration from './CustomerRegistration'
import OwnerBillPayments from './OwnerBillPayments'
import {
  billPaymentStatusLabel,
  getAccountsWithBalances,
  getPeriodBalances,
  type AccountSummary,
  type BillBalance,
  type BillPaymentStatus,
} from './ledgerApi'
import { formatRupees } from './money'
import CustomerWorkspace from './CustomerWorkspace'
import OwnerCustomers from './OwnerCustomers'
import OwnerDailyRecords from './OwnerDailyRecords'
import OwnerProducts from './OwnerProducts'
import OwnerSubscriptions from './OwnerSubscriptions'
import './App.css'

type Customer = {
  id: string
  name: string
  phone: string | null
  billingType: 'MONTHLY' | 'PREPAID'
  status: 'ACTIVE' | 'PAUSED' | 'INACTIVE' | 'PENDING'
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

// A small dairy has well under 50 bills a month, so filters below see the whole month on one page.
const PAGE_SIZE = 50
type OwnerSection = 'billing' | 'daily-records' | 'customers' | 'products' | 'subscriptions'

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
  return formatRupees(amount)
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
  const [authView, setAuthView] = useState<'sign-in' | 'register'>('sign-in')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [periodId, setPeriodId] = useState(previousMonthInIndia)
  const [period, setPeriod] = useState<BillingPeriod | null>(null)
  const [customers, setCustomers] = useState<Customer[]>([])
  const [bills, setBills] = useState<Page<Bill> | null>(null)
  const [balances, setBalances] = useState<Record<string, BillBalance>>({})
  const [accounts, setAccounts] = useState<AccountSummary[]>([])
  const [payFilter, setPayFilter] = useState<'ALL' | BillPaymentStatus>('ALL')
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
    setBalances({})
    setAccounts([])
    setPayFilter('ALL')
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
      const [periodResponse, customerResponse, billResponse, balanceResponse, accountResponse] = await Promise.all([
        apiRequest<BillingPeriod>(`/owner/billing-periods/${selectedPeriod}`, authToken),
        apiRequest<Customer[]>('/owner/customers', authToken),
        apiRequest<Page<Bill>>(`/owner/bills?period=${selectedPeriod}&page=${selectedPage}&size=${PAGE_SIZE}`, authToken),
        // Payment status is extra information: if it cannot load, billing itself still works.
        getPeriodBalances(authToken, selectedPeriod).catch(() => [] as BillBalance[]),
        getAccountsWithBalances(authToken).catch(() => [] as AccountSummary[]),
      ])
      if (requestId !== workspaceRequestId.current) return
      setPeriod(periodResponse)
      setCustomers(customerResponse)
      setBills(billResponse)
      setBalances(Object.fromEntries(balanceResponse.map((balance) => [balance.billId, balance])))
      setAccounts(accountResponse)
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
        ? 'Unable to connect to Radhika Farms. Check your connection and try again.'
        : cause instanceof Error ? cause.message : 'Sign in failed.')
    } finally {
      setLoginLoading(false)
    }
  }

  function completeRegistration(result: LoginResponse) {
    storeSessionToken(result.token)
    setError('')
    setAuthView('sign-in')
    setSession({ status: 'checking', token: result.token, user: null })
  }

  function showAuthView(view: 'sign-in' | 'register') {
    setError('')
    setPassword('')
    setAuthView(view)
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
      <div className="login-page">
        <header className="login-hero">
          <div className="login-hero-inner">
            <div className="login-hero-copy">
              <p className="eyebrow">Radhika Farms · Family dairy</p>
              <h1>Fresh milk <span>from our own farm.</span></h1>
              <p className="login-hero-lede">Collect your cow or buffalo milk each morning and evening. Every litre is recorded, so your bill always matches what you took home.</p>
            </div>
            <div className="login-hero-art" aria-hidden="true"><MilkCan /></div>
          </div>
        </header>
        <div className="marquee" aria-hidden="true">
          <div className="marquee-track">
            {[0, 1].map((copy) => (
              <span key={copy}>Cow milk ● Buffalo milk ● Cow ghee ● Buffalo ghee ● Morning &amp; evening ● From our own herd ●</span>
            ))}
          </div>
        </div>
        <main className="login-body">
          <section className="login-card" aria-label="Your account">
            <div className="auth-tabs" role="group" aria-label="Sign in or create an account">
              <button type="button" aria-pressed={authView === 'sign-in'} onClick={() => showAuthView('sign-in')}>Sign in</button>
              <button type="button" aria-pressed={authView === 'register'} onClick={() => showAuthView('register')}>Create account</button>
            </div>
            {authView === 'register' ? (
              <CustomerRegistration onRegistered={completeRegistration} onShowSignIn={() => showAuthView('sign-in')} />
            ) : (<>
              <p className="eyebrow">Welcome back</p>
              <h2>Sign in to your account.</h2>
              <p className="login-copy">Use your phone number and password. We’ll take you to the right page.</p>
              <form onSubmit={signIn} className="login-form">
                <label htmlFor="account-phone">Phone number</label>
                <input id="account-phone" type="tel" inputMode="numeric" autoComplete="username"
                  pattern="[0-9]{10}" maxLength={10} value={phone}
                  onChange={(event) => setPhone(event.target.value.replace(/\D/g, '').slice(0, 10))}
                  aria-describedby="phone-help" required />
                <span id="phone-help" className="form-hint">The 10-digit number the farm knows you by.</span>
                <label htmlFor="account-password">Password</label>
                <input id="account-password" type="password" autoComplete="current-password" value={password}
                  onChange={(event) => setPassword(event.target.value)} required />
                {error && <p className="alert error-alert" role="alert">{error}</p>}
                <button className="primary-button full-button" disabled={loginLoading} aria-busy={loginLoading}>
                  {loginLoading ? 'Signing in…' : 'Sign in →'}
                </button>
              </form>
            </>)}
          </section>
          <section className="login-story" aria-labelledby="farm-story">
            <h2 id="farm-story">Our cows. Our buffaloes. <em>Your milk.</em></h2>
            <p>Radhika Farms is a family dairy. The milk you collect comes straight from the animals we raise and care for ourselves, milked fresh for the morning and evening collections.</p>
            <ul className="product-lines">
              <li><strong>Cow milk</strong><span>Light and fresh, every morning and evening</span></li>
              <li><strong>Buffalo milk</strong><span>Rich and creamy, good for curd and tea</span></li>
              <li><strong>Cow ghee</strong><span>Made at the farm in small batches</span></li>
              <li><strong>Buffalo ghee</strong><span>Grainy, golden, slow-cooked</span></li>
            </ul>
            <p className="privacy-note">Your session is kept only in this browser tab.</p>
          </section>
        </main>
        <footer className="login-footer"><div><span>Radhika Farms</span><span>Fresh milk, honestly counted.</span></div></footer>
      </div>
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

  function showOwnerSection(section: OwnerSection) {
    workspaceRequestId.current += 1
    setLoading(false)
    setOwnerSection(section)
  }

  if (ownerSection === 'daily-records') {
    return (
      <OwnerDailyRecords
        token={session.token}
        onSignOut={signOut}
        onSessionExpired={expireSession}
        onShowBilling={() => setOwnerSection('billing')}
        onShowCustomers={() => showOwnerSection('customers')}
        onShowProducts={() => showOwnerSection('products')}
        onShowSubscriptions={() => showOwnerSection('subscriptions')}
      />
    )
  }

  if (ownerSection === 'customers') {
    return (
      <OwnerCustomers
        token={session.token}
        onSignOut={signOut}
        onSessionExpired={expireSession}
        onShowBilling={() => setOwnerSection('billing')}
        onShowDailyRecords={() => showOwnerSection('daily-records')}
        onShowProducts={() => showOwnerSection('products')}
        onShowSubscriptions={() => showOwnerSection('subscriptions')}
      />
    )
  }

  if (ownerSection === 'products') {
    return (
      <OwnerProducts
        token={session.token}
        onSignOut={signOut}
        onSessionExpired={expireSession}
        onShowBilling={() => setOwnerSection('billing')}
        onShowDailyRecords={() => showOwnerSection('daily-records')}
        onShowCustomers={() => showOwnerSection('customers')}
        onShowSubscriptions={() => showOwnerSection('subscriptions')}
      />
    )
  }

  if (ownerSection === 'subscriptions') {
    return (
      <OwnerSubscriptions
        token={session.token}
        onSignOut={signOut}
        onSessionExpired={expireSession}
        onShowBilling={() => setOwnerSection('billing')}
        onShowDailyRecords={() => showOwnerSection('daily-records')}
        onShowCustomers={() => showOwnerSection('customers')}
        onShowProducts={() => showOwnerSection('products')}
      />
    )
  }

  const monthlyCustomers = customers.filter((customer) => customer.billingType === 'MONTHLY')
  const selectedCustomer = monthlyCustomers.find((customer) => customer.id === customerId)
  const canClose = Boolean(period && period.status === 'OPEN' && period.period < previousMonthInIndia())

return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Radhika Farms home">
          <span className="brand-mark" aria-hidden="true">R</span><span>Radhika <span className="brand-light">Farms</span></span>
        </a>
        <div className="workspace-label">WORKSPACE</div>
        <nav aria-label="Main navigation">
          <button type="button" className="nav-item owner-nav-item active" aria-current="page" onClick={() => setOwnerSection('billing')}><span className="nav-icon" aria-hidden="true">▦</span> Billing</button>
          <button type="button" className="nav-item owner-nav-item" onClick={() => showOwnerSection('daily-records')}><span className="nav-icon" aria-hidden="true">◧</span> Daily records</button>
          <button type="button" className="nav-item owner-nav-item" onClick={() => showOwnerSection('customers')}><span className="nav-icon" aria-hidden="true">♢</span> Customers</button>
          <button type="button" className="nav-item owner-nav-item" onClick={() => showOwnerSection('products')}><span className="nav-icon" aria-hidden="true">◈</span> Products</button>
          <button type="button" className="nav-item owner-nav-item" onClick={() => showOwnerSection('subscriptions')}><span className="nav-icon" aria-hidden="true">◈</span> Subscriptions</button>
        </nav>
        <div className="sidebar-bottom">
          <div className="secure-note"><span className="secure-dot" /> Secure owner session</div>
          <button className="signout-button" onClick={signOut}>Sign out</button>
        </div>
      </aside>

      <main id="billing" className="main-content">
        <header className="topbar">
          <div className="topbar-greeting">
            <div className="breadcrumb">Workspace <span>/</span> Billing</div>
          </div>
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
              <p className="summary-foot">Total of collected and extra records</p>
            </article>
          </section>

          {Object.keys(balances).length > 0 && (() => {
            const list = Object.values(balances)
            const sum = (pick: (balance: BillBalance) => number) => list.reduce((total, balance) => total + pick(balance), 0)
            const owing = list.filter((balance) => balance.due > 0).length
            return (
              <section className="summary-grid payment-summary" aria-label="Payments for this month">
                <article className="summary-card"><p className="summary-label">BILLED</p><h2>{formatAmount(sum((b) => b.amountToPay))}</h2><p className="summary-foot">{list.length} issued bill{list.length === 1 ? '' : 's'}</p></article>
                <article className="summary-card"><p className="summary-label">RECEIVED</p><h2 className="money-credit">{formatAmount(sum((b) => b.paid))}</h2><p className="summary-foot">{list.length - owing} paid in full</p></article>
                <article className="summary-card"><p className="summary-label">STILL OWED</p><h2 className={owing ? 'money-owed' : 'money-credit'}>{formatAmount(sum((b) => b.due))}</h2><p className="summary-foot">{owing} customer{owing === 1 ? '' : 's'} still owe</p></article>
              </section>
            )
          })()}

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
            {Object.keys(balances).length > 0 && (
              <div className="pay-filters" role="group" aria-label="Filter by payment status">
                {(['ALL', 'UNPAID', 'PART_PAID', 'PAID'] as const).map((filter) => {
                  const count = filter === 'ALL' ? bills?.content.length ?? 0
                    : Object.values(balances).filter((balance) => balance.status === filter).length
                  return (
                    <button key={filter} type="button" aria-pressed={payFilter === filter} onClick={() => setPayFilter(filter)}>
                      {filter === 'ALL' ? 'All' : billPaymentStatusLabel(filter)} · {count}
                    </button>
                  )
                })}
              </div>
            )}
            <div className="table-wrap">
              <table>
                <thead><tr><th>CUSTOMER</th><th>PERIOD</th><th>RECORDS</th><th>TOTAL</th><th>STATUS</th><th>PAYMENT</th><th><span className="sr-only">Actions</span></th></tr></thead>
                <tbody>
                  {bills?.content.filter((bill) => payFilter === 'ALL' || balances[bill.id]?.status === payFilter).map((bill) => (
                    <tr key={bill.id} className={selectedBill?.id === bill.id ? 'selected-row' : ''}>
                      <td><button className="customer-cell" onClick={() => setSelectedBill(selectedBill?.id === bill.id ? null : bill)}>
                        <span className="customer-avatar">{bill.customerName.trim().charAt(0).toUpperCase()}</span>
                        <span><strong>{bill.customerName}</strong><small>{bill.customerPhone ?? bill.customerId.slice(0, 8)}</small></span>
                      </button></td>
                      <td>{formatMonth(bill.period)}</td>
                      <td>{bill.lines.length} <span className="muted">items</span></td>
                      <td className="amount-cell">{formatAmount(bill.totalAmount)}</td>
                      <td><span className={`status-pill ${bill.status.toLowerCase()}`}>{bill.status}</span></td>
                      <td>{balances[bill.id] ? (
                        <span className="pay-cell">
                          <span className={`status-pill pay-${balances[bill.id].status.toLowerCase()}`}>{billPaymentStatusLabel(balances[bill.id].status)}</span>
                          {balances[bill.id].due > 0 && <small>{formatAmount(balances[bill.id].due)} due</small>}
                        </span>
                      ) : <span className="muted">—</span>}</td>
                      <td className="row-action">{bill.status === 'DRAFT' && <button className="issue-button"
                        onClick={() => void issueBill(bill)} disabled={busyAction !== ''}>
                        {busyAction === bill.id ? 'Issuing…' : 'Issue'}
                      </button>}</td>
                    </tr>
                  ))}
                  {!loading && bills?.content.length === 0 && <tr><td colSpan={7} className="empty-state">
                    <span className="empty-icon">▤</span><strong>No bills for this month yet</strong>
                    <span>{period?.status === 'CLOSED'
                      ? 'Generate a draft for a monthly customer with chargeable records.'
                      : 'Close the period before generating bills.'}</span>
                  </td></tr>}
                  {loading && !bills && <tr><td colSpan={7} className="empty-state">Loading billing records…</td></tr>}
                </tbody>
              </table>
            </div>
            {selectedBill && (
              <BillDetail
                bill={selectedBill}
                balance={balances[selectedBill.id] ?? null}
                token={token}
                onChanged={() => void loadWorkspace(token, periodId, page)}
                onSessionExpired={expireSession}
                onClose={() => setSelectedBill(null)}
              />
            )}
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
          {accounts.length > 0 && (
            <section className="bills-section" aria-labelledby="balances-title">
              <div className="section-heading">
                <div><p className="eyebrow">ALL MONTHS</p><h2 id="balances-title">Customers with balances</h2></div>
              </div>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>CUSTOMER</th><th>PHONE</th><th>OWES</th><th>CREDIT</th></tr></thead>
                  <tbody>
                    {accounts.map((account) => (
                      <tr key={account.customerId}>
                        <td><strong>{account.name}</strong></td>
                        <td>{account.phone ?? '—'}</td>
                        <td className={`amount-cell${account.amountDue > 0 ? ' money-owed' : ''}`}>{formatAmount(account.amountDue)}</td>
                        <td className={`amount-cell${account.credit > 0 ? ' money-credit' : ''}`}>{formatAmount(account.credit)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
          <footer className="page-footer"><span>Radhika Farms <b>·</b> Owner billing</span><span>All times and business dates follow Asia/Kolkata.</span></footer>
        </div>
      </main>
    </div>
  )
}

type BillDetailProps = {
  bill: Bill
  balance: BillBalance | null
  token: string
  onChanged: () => void
  onSessionExpired: () => void
  onClose: () => void
}

function BillDetail({ bill, balance, token, onChanged, onSessionExpired, onClose }: BillDetailProps) {
  return (
    <section className="bill-detail" aria-label={`Bill details for ${bill.customerName}`}>
      <div className="detail-heading">
        <p className="eyebrow">BILL SNAPSHOT · {bill.customerName}</p>
        <button className="close-detail" onClick={onClose} aria-label="Close bill details">×</button>
      </div>
      <BillReceipt
        key={bill.id}
        billId={bill.id}
        period={bill.period}
        periodStart={bill.periodStart}
        periodEnd={bill.periodEnd}
        status={bill.status}
        issuedAt={bill.issuedAt}
        totalAmount={bill.totalAmount}
        lines={bill.lines}
        customerName={bill.customerName}
        customerPhone={bill.customerPhone}
        customerAddress={bill.customerAddress}
        balance={balance}
      />
      <p className="snapshot-note">Values are preserved from the bill snapshot and are not recalculated from current product prices.</p>
      {bill.status === 'ISSUED' ? (
        <OwnerBillPayments
          key={bill.customerId}
          token={token}
          customerId={bill.customerId}
          customerName={bill.customerName}
          billId={bill.id}
          onChanged={onChanged}
          onSessionExpired={onSessionExpired}
        />
      ) : (
        <p className="alert pending-alert">Issue this bill before recording payments against it.</p>
      )}
    </section>
  )
}

function MilkCan() {
  return (
    <svg viewBox="0 0 220 280" focusable="false">
      <rect x="88" y="6" width="44" height="18" rx="6" fill="var(--butter)" />
      <rect x="80" y="20" width="60" height="14" rx="4" fill="var(--on-band)" />
      <path d="M84 34 h52 l8 34 h-68 z" fill="var(--on-band)" />
      <path d="M60 66 h100 q18 0 22 18 l10 160 q2 26 -24 26 h-116 q-26 0 -24 -26 l10 -160 q4 -18 22 -18z" fill="var(--on-band)" />
      <rect x="34" y="128" width="152" height="54" fill="var(--butter)" />
      <text x="110" y="155" textAnchor="middle" dominantBaseline="central" fontFamily="Fraunces, Georgia, serif" fontWeight="900" fontSize="30" letterSpacing="3" fill="var(--band)">MILK</text>
      <path d="M42 96 q-26 6 -22 38" stroke="var(--on-band)" strokeWidth="8" fill="none" strokeLinecap="round" />
      <path d="M178 96 q26 6 22 38" stroke="var(--on-band)" strokeWidth="8" fill="none" strokeLinecap="round" />
      <rect x="30" y="206" width="160" height="8" fill="var(--sky)" />
    </svg>
  )
}

export default App

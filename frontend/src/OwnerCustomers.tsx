import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { isUnauthenticatedError } from './authApi.ts'
import {
  billingTypeLabel,
  createOwnerCustomer,
  customerStatusLabel,
  filterOwnerCustomers,
  getOwnerCustomers,
  ownerCustomerErrorMessage,
  updateOwnerCustomer,
  type OwnerBillingType,
  type OwnerCustomer,
  type OwnerCustomerStatus,
} from './ownerCustomersApi'

type OwnerCustomersProps = {
  token: string
  onSignOut: () => void
  onSessionExpired: () => void
  onShowBilling: () => void
  onShowDailyRecords: () => void
  onShowProducts: () => void
  onShowSubscriptions: () => void
}

const statuses: { value: OwnerCustomerStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'PAUSED', label: 'Paused' },
  { value: 'INACTIVE', label: 'Inactive' },
  { value: 'PENDING', label: 'Pending approval' },
]

const billingTypes: { value: OwnerBillingType; label: string }[] = [
  { value: 'MONTHLY', label: 'Monthly' },
  { value: 'PREPAID', label: 'Prepaid' },
]

function todayInIndia() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const value = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}-${value('month')}-${value('day')}`
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'Asia/Kolkata',
  }).format(new Date(`${value}T00:00:00+05:30`))
}

// Mirrors the server contract only: a name is required, a new account needs a
// 10-digit phone and a password of at least 8 characters, and the replacement
// fields must all be present. It is a usability guard, not a second rule engine.
function validateCustomer(input: {
  name: string
  phone: string
  password: string
  joinDate: string
  creating: boolean
}) {
  if (!input.name.trim()) return 'Enter the customer name.'
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.joinDate)) return 'Choose a valid join date.'
  if (!input.creating) return ''
  if (!/^\d{10}$/.test(input.phone)) return 'Phone must be a 10-digit number.'
  if (input.password.length < 8) return 'Password must be at least 8 characters.'
  return ''
}

export default function OwnerCustomers({
  token,
  onSignOut,
  onSessionExpired,
  onShowBilling,
  onShowDailyRecords,
  onShowProducts,
  onShowSubscriptions,
}: OwnerCustomersProps) {
  const today = todayInIndia()
  const [customers, setCustomers] = useState<OwnerCustomer[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<'' | OwnerCustomerStatus>('')
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [address, setAddress] = useState('')
  const [billingType, setBillingType] = useState<OwnerBillingType>('MONTHLY')
  const [status, setStatus] = useState<OwnerCustomerStatus>('ACTIVE')
  const [joinDate, setJoinDate] = useState(today)
  const [entryError, setEntryError] = useState('')
  const [notice, setNotice] = useState('')
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const [editing, setEditing] = useState<OwnerCustomer | null>(null)
  const requestId = useRef(0)

  const visibleCustomers = useMemo(
    () => filterOwnerCustomers(customers, { query: searchQuery, status: statusFilter }),
    [customers, searchQuery, statusFilter],
  )

  const handleError = useCallback((error: unknown) => {
    if (isUnauthenticatedError(error)) onSessionExpired()
    return ownerCustomerErrorMessage(error)
  }, [onSessionExpired])

  const loadCustomers = useCallback(async () => {
    const request = ++requestId.current
    setLoading(true)
    setLoadError('')
    try {
      const results = await getOwnerCustomers(token)
      if (request === requestId.current) setCustomers(results)
    } catch (error) {
      if (request === requestId.current) setLoadError(handleError(error))
    } finally {
      if (request === requestId.current) setLoading(false)
    }
  }, [handleError, token])

  useEffect(() => {
    void loadCustomers()
    return () => {
      requestId.current += 1
    }
  }, [loadCustomers])

  function resetForm() {
    setName('')
    setPhone('')
    setPassword('')
    setAddress('')
    setBillingType('MONTHLY')
    setStatus('ACTIVE')
    setJoinDate(today)
    setEditing(null)
    setEntryError('')
  }

  function beginEdit(customer: OwnerCustomer) {
    setEditing(customer)
    setName(customer.name)
    setPhone(customer.phone ?? '')
    setPassword('')
    setAddress(customer.address ?? '')
    setBillingType(customer.billingType)
    setStatus(customer.status)
    setJoinDate(customer.joinDate)
    setEntryError('')
    setNotice('')
  }

  async function saveCustomer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (savingRef.current) return
    setEntryError('')
    setNotice('')
    const creating = !editing
    const validationMessage = validateCustomer({ name, phone, password, joinDate, creating })
    if (validationMessage) {
      setEntryError(validationMessage)
      return
    }
    savingRef.current = true
    setSaving(true)
    try {
      if (editing) {
        await updateOwnerCustomer(token, editing.id, {
          name: name.trim(),
          address: address.trim() || null,
          billingType,
          status,
          joinDate,
        })
        setNotice(`Customer ${name.trim()} updated.`)
        resetForm()
      } else {
        await createOwnerCustomer(token, {
          name: name.trim(),
          phone,
          password,
          address: address.trim() || null,
          billingType,
          status,
          joinDate,
        })
        setNotice(`Customer ${name.trim()} created. Share the initial password securely; it cannot be reset from this screen.`)
        resetForm()
      }
      await loadCustomers()
    } catch (error) {
      setEntryError(handleError(error))
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  async function changeStatus(customer: OwnerCustomer, nextStatus: OwnerCustomerStatus) {
    if (savingRef.current) return
    setEntryError('')
    setNotice('')
    savingRef.current = true
    setSaving(true)
    try {
      await updateOwnerCustomer(token, customer.id, {
        name: customer.name,
        address: customer.address,
        billingType: customer.billingType,
        status: nextStatus,
        joinDate: customer.joinDate,
      })
      setNotice(`Customer ${customer.name} is now ${customerStatusLabel(nextStatus).toLowerCase()}.`)
      await loadCustomers()
    } catch (error) {
      setEntryError(handleError(error))
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  return (
    <div className="app-shell owner-catalog-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Milk Web home">
          <span className="brand-mark">m</span><span>milk<span className="brand-light">web</span></span>
        </a>
        <div className="workspace-label">WORKSPACE</div>
        <nav aria-label="Main navigation">
          <button type="button" className="nav-item owner-nav-item" onClick={onShowBilling}>
            <span className="nav-icon" aria-hidden="true">▤</span> Billing
          </button>
          <button type="button" className="nav-item owner-nav-item" onClick={onShowDailyRecords}>
            <span className="nav-icon" aria-hidden="true">◷</span> Daily records
          </button>
          <button type="button" className="nav-item owner-nav-item active" aria-current="page">
            <span className="nav-icon" aria-hidden="true">♧</span> Customers
          </button>
          <button type="button" className="nav-item owner-nav-item" onClick={onShowProducts}>
            <span className="nav-icon" aria-hidden="true">◈</span> Products
          </button>
          <button type="button" className="nav-item owner-nav-item" onClick={onShowSubscriptions}>
            <span className="nav-icon" aria-hidden="true">◈</span> Subscriptions
          </button>
        </nav>
        <div className="sidebar-bottom">
          <div className="secure-note"><span className="secure-dot" /> Secure owner session</div>
          <button className="signout-button" onClick={onSignOut}>Sign out</button>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumb">Workspace <span>/</span> Customers</div>
          <div className="topbar-right"><span className="today-label">{formatDate(today)}</span><span className="avatar">OW</span></div>
        </header>
        <div className="page-content owner-catalog">
          <div className="page-heading">
            <div>
              <p className="eyebrow">CUSTOMER ACCOUNTS</p>
              <h1>Customers</h1>
              <p className="page-subtitle">Create customer logins and keep contact, billing, and account details current.</p>
            </div>
          </div>

          {notice && <p className="alert success-alert" role="status">{notice}</p>}
          <section className="catalog-entry-card" aria-labelledby="customer-entry-title">
            <div className="section-heading">
              <div><p className="eyebrow">{editing ? 'EDIT CUSTOMER' : 'NEW CUSTOMER'}</p><h2 id="customer-entry-title">{editing ? `Edit ${editing.name}` : 'Create a customer account'}</h2></div>
              {editing && <button type="button" className="text-button" onClick={resetForm}>Cancel edit</button>}
            </div>
            {loading ? (
              <div className="customer-state" role="status">Loading customers…</div>
            ) : loadError ? (
              <section className="customer-state customer-error">
                <h2>We could not load customers</h2>
                <p className="alert error-alert" role="alert">{loadError}</p>
                <button className="secondary-button" onClick={() => void loadCustomers()}>Try again</button>
              </section>
            ) : (
              <>
                <form className="catalog-form" aria-label="Customer details" onSubmit={(event) => void saveCustomer(event)} noValidate>
                  <label htmlFor="customer-name">Name
                    <input id="customer-name" type="text" maxLength={100} value={name} onChange={(event) => setName(event.target.value)} required />
                  </label>
                  <div className="catalog-field">
                    <label htmlFor="customer-phone">Phone</label>
                    <input id="customer-phone" type="text" inputMode="numeric" maxLength={10} value={phone}
                      onChange={(event) => setPhone(event.target.value.replace(/\D/g, ''))}
                      readOnly={Boolean(editing)} aria-readonly={Boolean(editing)}
                      aria-describedby={editing ? 'customer-phone-help' : undefined} required />
                    {editing && (
                      <span id="customer-phone-help" className="form-hint">
                        A phone number cannot be changed after the account is created.
                      </span>
                    )}
                  </div>
                  {!editing && (
                    <div className="catalog-field">
                      <label htmlFor="customer-password">Initial password</label>
                      <input id="customer-password" type="password" autoComplete="new-password" value={password}
                        onChange={(event) => setPassword(event.target.value)}
                        minLength={8} aria-describedby="customer-password-help" required />
                      <span id="customer-password-help" className="form-hint">
                        At least 8 characters. This creates the customer login, so share it with the customer over a trusted channel.
                        There is no password-reset feature; the owner cannot change it later from this screen.
                      </span>
                    </div>
                  )}
                  <label htmlFor="customer-address">Address
                    <input id="customer-address" type="text" maxLength={500} value={address} onChange={(event) => setAddress(event.target.value)} />
                  </label>
                  <label htmlFor="customer-billing-type">Billing type
                    <select id="customer-billing-type" value={billingType}
                      onChange={(event) => setBillingType(event.target.value as OwnerBillingType)}>
                      {billingTypes.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                    </select>
                  </label>
                  <label htmlFor="customer-status">Status
                    <select id="customer-status" value={status}
                      onChange={(event) => setStatus(event.target.value as OwnerCustomerStatus)}>
                      {statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                    </select>
                  </label>
                  <label htmlFor="customer-join-date">Join date
                    <input id="customer-join-date" type="date" value={joinDate} onChange={(event) => setJoinDate(event.target.value)} required />
                  </label>
                  <div className="catalog-submit">
                    {entryError && <p className="alert error-alert" role="alert">{entryError}</p>}
                    <button className="primary-button" disabled={saving} aria-busy={saving}>
                      {saving ? 'Saving…' : editing ? 'Save customer' : 'Create customer'}
                    </button>
                  </div>
                </form>
                <p className="customer-readonly-note">
                  Monthly customers appear in monthly bill generation; prepaid customers are excluded.
                  Changing the billing type affects future bills only. Deactivating a customer does not remove records already collected.
                </p>
              </>
            )}
          </section>

          <section className="catalog-review" aria-labelledby="customer-review-title">
            <div className="section-heading">
              <div><p className="eyebrow">CUSTOMER DIRECTORY</p><h2 id="customer-review-title">All customers</h2></div>
              <button type="button" className="text-button" onClick={() => void loadCustomers()} disabled={loading}>
                {loading ? 'Refreshing…' : 'Refresh'}
              </button>
            </div>
            <form className="catalog-filters" aria-label="Filter customers" onSubmit={(event) => event.preventDefault()}>
              <div className="catalog-field">
                <label htmlFor="customer-search">Search</label>
                <input id="customer-search" type="search" value={searchQuery} placeholder="Name or phone"
                  onChange={(event) => setSearchQuery(event.target.value)} aria-describedby="customer-search-help" />
                <span id="customer-search-help" className="form-hint">Filtering happens in this browser over the full customer list.</span>
              </div>
              <label htmlFor="customer-status-filter">Status
                <select id="customer-status-filter" value={statusFilter}
                  onChange={(event) => setStatusFilter(event.target.value as '' | OwnerCustomerStatus)}>
                  <option value="">All statuses</option>
                  {statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                </select>
              </label>
            </form>

            {!loading && !loadError && customers.length === 0 ? (
              <div className="customer-state"><h3>No customers yet</h3><p>Create the first customer account using the form above.</p></div>
            ) : !loadError && visibleCustomers.length === 0 ? (
              <div className="customer-state"><h3>No customers match</h3><p>Clear the search or choose a different status.</p></div>
            ) : !loadError ? (
              <>
                <p className="action-hint">Showing {visibleCustomers.length} of {customers.length} customers.</p>
                <ul className="catalog-list" aria-label="Customers">
                  {visibleCustomers.map((customer) => (
                    <li className="catalog-card" key={customer.id}>
                      <div className="catalog-card-primary">
                        <div>
                          <h3>{customer.name}</h3>
                          <p>{customer.phone ?? 'No phone on file'} · joined {formatDate(customer.joinDate)}</p>
                        </div>
                        <span className={`status-pill customer-status ${customer.status.toLowerCase()}`}>
                          {customerStatusLabel(customer.status)}
                        </span>
                      </div>
                      <dl className="catalog-values">
                        <div><dt>Billing type</dt><dd>{billingTypeLabel(customer.billingType)}</dd></div>
                        <div><dt>Address</dt><dd>{customer.address ?? '—'}</dd></div>
                      </dl>
                      <div className="catalog-actions">
                        <button type="button" className="text-button" onClick={() => beginEdit(customer)}>Edit details</button>
                        {customer.status === 'ACTIVE' ? (
                          <button type="button" className="text-button" disabled={saving}
                            onClick={() => void changeStatus(customer, 'INACTIVE')}>Deactivate</button>
                        ) : customer.status === 'PENDING' ? (
                          <button type="button" className="text-button" disabled={saving}
                            onClick={() => void changeStatus(customer, 'ACTIVE')}>Approve</button>
                        ) : (
                          <button type="button" className="text-button" disabled={saving}
                            onClick={() => void changeStatus(customer, 'ACTIVE')}>Reactivate</button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
          </section>
          <footer className="page-footer"><span>Milk Web <b>·</b> Owner workspace</span><span>Customer accounts</span></footer>
        </div>
      </main>
    </div>
  )
}

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { isUnauthenticatedError } from './authApi.ts'
import {
  getOwnerCustomers,
  ownerCustomerErrorMessage,
  type OwnerCustomer,
} from './ownerCustomersApi.ts'
import {
  getOwnerProducts,
  ownerProductErrorMessage,
  type OwnerProduct,
} from './ownerProductsApi.ts'
import {
  createOwnerSubscription,
  getOwnerSubscriptions,
  ownerSubscriptionErrorMessage,
  ownerSubscriptionStatusLabel,
  type CreateOwnerSubscription,
  type OwnerSubscription,
  type OwnerSubscriptionFilters,
} from './ownerSubscriptionsApi.ts'

type OwnerSubscriptionsProps = {
  token: string
  onSignOut: () => void
  onSessionExpired: () => void
  onShowBilling: () => void
  onShowDailyRecords: () => void
  onShowCustomers: () => void
  onShowProducts: () => void
}

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

function formatDateOrDash(value: string | null) {
  if (!value) return '—'
  return formatDate(value)
}

export default function OwnerSubscriptions({
  token,
  onSignOut,
  onSessionExpired,
  onShowBilling,
  onShowDailyRecords,
  onShowCustomers,
  onShowProducts,
}: OwnerSubscriptionsProps) {
  const today = todayInIndia()
  const [subscriptions, setSubscriptions] = useState<OwnerSubscription[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')

  const [customers, setCustomers] = useState<OwnerCustomer[]>([])
  const [customersLoading, setCustomersLoading] = useState(true)
  const [customersError, setCustomersError] = useState('')

  const [products, setProducts] = useState<OwnerProduct[]>([])
  const [productsLoading, setProductsLoading] = useState(true)
  const [productsError, setProductsError] = useState('')

  const [filters, setFilters] = useState<OwnerSubscriptionFilters>({
    customerId: null,
    productId: null,
    status: '',
    effectiveOn: null,
  })

  const filtersRef = useRef(filters)

  // Create form state
  const [formCustomerId, setFormCustomerId] = useState('')
  const [formProductId, setFormProductId] = useState('')
  const [formDefaultQuantity, setFormDefaultQuantity] = useState('')
  const [formStartDate, setFormStartDate] = useState(today)
  const [formEndDate, setFormEndDate] = useState('')
  const [formError, setFormError] = useState('')
  const [formNotice, setFormNotice] = useState('')
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)

  const requestId = useRef(0)
  const customersRequestId = useRef(0)
  const productsRequestId = useRef(0)

  // Quantity validation: max 5 integer digits + 3 decimal places
  function validateQuantity(value: string): string {
    if (!value.trim()) return 'Enter a default quantity.'
    const num = Number(value)
    if (Number.isNaN(num) || num <= 0) return 'Quantity must be greater than zero.'
    if (!/^\d{1,5}(\.\d{1,3})?$/.test(value)) return 'Maximum 5 integer digits and 3 decimal places.'
    return ''
  }

  const validateForm = useCallback((): string => {
    if (!formCustomerId) return 'Select a customer.'
    if (!formProductId) return 'Select a product.'
    const quantityError = validateQuantity(formDefaultQuantity)
    if (quantityError) return quantityError
    if (!/^\d{4}-\d{2}-\d{2}$/.test(formStartDate)) return 'Choose a valid start date.'
    if (formEndDate && !/^\d{4}-\d{2}-\d{2}$/.test(formEndDate)) return 'Choose a valid end date or leave empty.'
    if (formEndDate && formEndDate < formStartDate) return 'End date cannot be before start date.'
    return ''
  }, [formCustomerId, formProductId, formDefaultQuantity, formStartDate, formEndDate])

  const resetForm = useCallback(() => {
    setFormCustomerId('')
    setFormProductId('')
    setFormDefaultQuantity('')
    setFormStartDate(today)
    setFormEndDate('')
    setFormError('')
    setFormNotice('')
  }, [today])

  const handleError = useCallback((error: unknown) => {
    if (isUnauthenticatedError(error)) onSessionExpired()
    return ownerSubscriptionErrorMessage(error)
  }, [onSessionExpired])

  const handleCustomersError = useCallback((error: unknown) => {
    if (isUnauthenticatedError(error)) onSessionExpired()
    return ownerCustomerErrorMessage(error)
  }, [onSessionExpired])

  const handleProductsError = useCallback((error: unknown) => {
    if (isUnauthenticatedError(error)) onSessionExpired()
    return ownerProductErrorMessage(error)
  }, [onSessionExpired])

  const loadCustomers = useCallback(async () => {
    const request = ++customersRequestId.current
    setCustomersLoading(true)
    setCustomersError('')
    try {
      const results = await getOwnerCustomers(token)
      if (request === customersRequestId.current) setCustomers(results)
    } catch (error) {
      if (request === customersRequestId.current) setCustomersError(handleCustomersError(error))
    } finally {
      if (request === customersRequestId.current) setCustomersLoading(false)
    }
  }, [handleCustomersError, token])

  const loadProducts = useCallback(async () => {
    const request = ++productsRequestId.current
    setProductsLoading(true)
    setProductsError('')
    try {
      const results = await getOwnerProducts(token)
      if (request === productsRequestId.current) setProducts(results)
    } catch (error) {
      if (request === productsRequestId.current) setProductsError(handleProductsError(error))
    } finally {
      if (request === productsRequestId.current) setProductsLoading(false)
    }
  }, [handleProductsError, token])

  const loadSubscriptions = useCallback(async (
    requestedFilters: OwnerSubscriptionFilters = filters,
  ) => {
    const request = ++requestId.current
    setLoading(true)
    setLoadError('')
    try {
      const results = await getOwnerSubscriptions(token, requestedFilters)
      if (request === requestId.current) setSubscriptions(results)
    } catch (error) {
      if (request === requestId.current) setLoadError(handleError(error))
    } finally {
      if (request === requestId.current) setLoading(false)
    }
  }, [filters, handleError, token])

  const changeFilters = useCallback((nextFilters: Partial<OwnerSubscriptionFilters>) => {
    const next = { ...filtersRef.current, ...nextFilters }
    filtersRef.current = next
    setFilters(next)
  }, [])

  const clearFilters = useCallback(() => {
    const cleared: OwnerSubscriptionFilters = {
      customerId: null,
      productId: null,
      status: '',
      effectiveOn: null,
    }
    filtersRef.current = cleared
    setFilters(cleared)
  }, [])

  const createSubscription = useCallback(async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (savingRef.current) return
    setFormError('')
    setFormNotice('')

    const validationMessage = validateForm()
    if (validationMessage) {
      setFormError(validationMessage)
      return
    }

    savingRef.current = true
    setSaving(true)
    try {
      const payload: CreateOwnerSubscription = {
        customerId: formCustomerId,
        productId: formProductId,
        defaultQuantity: Number(formDefaultQuantity),
        startDate: formStartDate,
        endDate: formEndDate || null,
      }
      await createOwnerSubscription(token, payload)
      resetForm()
      setFormNotice('Subscription created. It starts ACTIVE.')
      await loadSubscriptions(filtersRef.current)
    } catch (error) {
      setFormError(handleError(error))
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }, [formCustomerId, formProductId, formDefaultQuantity, formStartDate, formEndDate, validateForm, resetForm, handleError, token, loadSubscriptions])

  const hasActiveFilters = filters.customerId !== null
    || filters.productId !== null
    || filters.status !== ''
    || filters.effectiveOn !== null

  useEffect(() => {
    void loadCustomers()
    void loadProducts()
    return () => {
      customersRequestId.current += 1
      productsRequestId.current += 1
    }
  }, [loadCustomers, loadProducts])

  useEffect(() => {
    void loadSubscriptions()
    return () => {
      requestId.current += 1
    }
  }, [loadSubscriptions])

  const activeCustomer = customers.find((c) => c.id === filters.customerId)
  const activeProduct = products.find((p) => p.id === filters.productId)

  const activeCustomers = customers.filter((c) => c.status === 'ACTIVE')
  const activeProducts = products.filter((p) => p.status === 'ACTIVE')

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
          <button type="button" className="nav-item owner-nav-item" onClick={onShowCustomers}>
            <span className="nav-icon" aria-hidden="true">♧</span> Customers
          </button>
          <button type="button" className="nav-item owner-nav-item" onClick={onShowProducts}>
            <span className="nav-icon" aria-hidden="true">◈</span> Products
          </button>
          <button type="button" className="nav-item owner-nav-item active" aria-current="page">
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
          <div className="breadcrumb">Workspace <span>/</span> Subscriptions</div>
          <div className="topbar-right"><span className="today-label">{formatDate(today)}</span><span className="avatar">OW</span></div>
        </header>
        <div className="page-content owner-catalog">
          <div className="page-heading">
            <div>
              <p className="eyebrow">SUBSCRIPTION MANAGEMENT</p>
              <h1>Subscriptions</h1>
              <p className="page-subtitle">Review and manage customer subscriptions for recurring dairy products.</p>
            </div>
          </div>

          {formNotice && <p className="alert success-alert" role="status">{formNotice}</p>}
          {customersError && <p className="alert error-alert" role="alert">{customersError}</p>}
          {productsError && <p className="alert error-alert" role="alert">{productsError}</p>}
          <section className="catalog-entry-card" aria-labelledby="subscription-entry-title">
            <div className="section-heading">
              <div><p className="eyebrow">NEW SUBSCRIPTION</p><h2 id="subscription-entry-title">Create a subscription</h2></div>
            </div>
            {loading && <div className="customer-state" role="status">Loading subscriptions…</div>}
            {loadError && (
              <section className="customer-state customer-error">
                <h2>We could not load subscriptions</h2>
                <p className="alert error-alert" role="alert">{loadError}</p>
                <button type="button" className="secondary-button" onClick={() => void loadSubscriptions()}>Try again</button>
              </section>
            )}
            <form className="catalog-form" aria-label="Subscription details" onSubmit={(event) => void createSubscription(event)} noValidate>
                  <div className="catalog-field">
                    <label htmlFor="subscription-customer">Customer <span className="required" aria-hidden="true">*</span>
                      <select
                        id="subscription-customer"
                        value={formCustomerId}
                        onChange={(event) => setFormCustomerId(event.target.value)}
                        disabled={customersLoading}
                        required
                      >
                        <option value="">Select a customer</option>
                        {activeCustomers.map((customer) => (
                          <option key={customer.id} value={customer.id}>
                            {customer.name}{customer.phone ? ` · ${customer.phone}` : ''}
                          </option>
                        ))}
                      </select>
                    </label>
                    <span className="form-hint">Only active customers can be selected.</span>
                  </div>
                  <div className="catalog-field">
                    <label htmlFor="subscription-product">Product <span className="required" aria-hidden="true">*</span>
                      <select
                        id="subscription-product"
                        value={formProductId}
                        onChange={(event) => setFormProductId(event.target.value)}
                        disabled={productsLoading}
                        required
                      >
                        <option value="">Select a product</option>
                        {activeProducts.map((product) => (
                          <option key={product.id} value={product.id}>
                            {product.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <span className="form-hint">Only active products can be selected.</span>
                  </div>
                  <div className="catalog-field">
                    <label htmlFor="subscription-default-quantity">Default quantity <span className="required" aria-hidden="true">*</span>
                      <input
                        id="subscription-default-quantity"
                        type="text"
                        inputMode="decimal"
                        value={formDefaultQuantity}
                        onChange={(event) => setFormDefaultQuantity(event.target.value)}
                        placeholder="e.g., 1.5"
                        maxLength={9}
                        required
                      />
                    </label>
                    <span className="form-hint">
                      Recurring quantity per delivery. Maximum 5 integer digits and 3 decimal places.
                    </span>
                  </div>
                  <div className="catalog-field">
                    <label htmlFor="subscription-start-date">Start date <span className="required" aria-hidden="true">*</span>
                      <input
                        id="subscription-start-date"
                        type="date"
                        value={formStartDate}
                        onChange={(event) => setFormStartDate(event.target.value)}
                        required
                      />
                    </label>
                  </div>
                  <div className="catalog-field">
                    <label htmlFor="subscription-end-date">End date (optional)
                      <input
                        id="subscription-end-date"
                        type="date"
                        value={formEndDate}
                        onChange={(event) => setFormEndDate(event.target.value)}
                      />
                    </label>
                    <span className="form-hint">Leave empty for no end date. Must be on or after start date if provided.</span>
                  </div>
                  <div className="catalog-submit">
                    {formError && <p className="alert error-alert" role="alert">{formError}</p>}
                    <button className="primary-button" disabled={saving || customersLoading || productsLoading} aria-busy={saving}>
                      {saving ? 'Creating…' : 'Create subscription'}
                    </button>
                  </div>
                </form>
                <p className="customer-readonly-note">
                  New subscriptions start <strong>ACTIVE</strong>. The default quantity is the recurring amount per delivery,
                  not a historical billing amount. Customer and product must be active. End date is optional.
                </p>
          </section>

          <section className="catalog-review" aria-labelledby="subscription-review-title">
            <div className="section-heading">
              <div><p className="eyebrow">ALL SUBSCRIPTIONS</p><h2 id="subscription-review-title">Subscriptions</h2></div>
              <button type="button" className="text-button" onClick={() => void loadSubscriptions()} disabled={loading}>
                {loading ? 'Refreshing…' : 'Refresh'}
              </button>
            </div>

            <form className="catalog-filters" aria-label="Filter subscriptions" onSubmit={(event) => event.preventDefault()}>
              <div className="catalog-field">
                <label htmlFor="subscription-filter-customer">Customer
                  <select
                    id="subscription-filter-customer"
                    value={filters.customerId ?? ''}
                    onChange={(event) => changeFilters({ customerId: event.target.value || null })}
                    disabled={customersLoading}
                  >
                    <option value="">All customers</option>
                    {customers.map((customer) => (
                      <option key={customer.id} value={customer.id}>
                        {customer.name}{customer.phone ? ` · ${customer.phone}` : ''}{customer.status !== 'ACTIVE' ? ` · ${customer.status.toLowerCase()}` : ''}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <div className="catalog-field">
                <label htmlFor="subscription-filter-product">Product
                  <select
                    id="subscription-filter-product"
                    value={filters.productId ?? ''}
                    onChange={(event) => changeFilters({ productId: event.target.value || null })}
                    disabled={productsLoading}
                  >
                    <option value="">All products</option>
                    {products.map((product) => (
                      <option key={product.id} value={product.id}>
                        {product.name}{product.status !== 'ACTIVE' ? ' · inactive' : ''}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <div className="catalog-field">
                <label htmlFor="subscription-filter-status">Status
                  <select
                    id="subscription-filter-status"
                    value={filters.status}
                    onChange={(event) => changeFilters({ status: event.target.value as '' | 'ACTIVE' | 'PAUSED' | 'CANCELLED' })}
                  >
                    <option value="">All statuses</option>
                    <option value="ACTIVE">Active</option>
                    <option value="PAUSED">Paused</option>
                    <option value="CANCELLED">Cancelled</option>
                  </select>
                </label>
              </div>
              <div className="catalog-field">
                <label htmlFor="subscription-filter-effective-on">Effective on
                  <input
                    id="subscription-filter-effective-on"
                    type="date"
                    value={filters.effectiveOn ?? ''}
                    max={today}
                    onChange={(event) => changeFilters({ effectiveOn: event.target.value || null })}
                  />
                </label>
              </div>
              {hasActiveFilters && (
                <button type="button" className="secondary-button" onClick={clearFilters}>
                  Clear filters
                </button>
              )}
            </form>

            {loading ? (
              <div className="customer-state" role="status">Loading subscriptions…</div>
            ) : loadError ? (
              <section className="customer-state customer-error">
                <h2>We could not load subscriptions</h2>
                <p className="alert error-alert" role="alert">{loadError}</p>
                <button className="secondary-button" onClick={() => void loadSubscriptions()}>Try again</button>
              </section>
            ) : subscriptions.length === 0 ? (
              <div className="customer-state"><h3>No subscriptions found</h3><p>{hasActiveFilters ? 'Try adjusting your filters.' : 'Subscriptions will appear here once created.'}</p></div>
            ) : (
              <>
                {hasActiveFilters && (
                  <p className="action-hint">
                    Showing filtered results.{' '}
                    <span>
                      {activeCustomer ? `Customer: ${activeCustomer.name}` : ''}
                      {activeCustomer && activeProduct ? ' · ' : ''}
                      {activeProduct ? `Product: ${activeProduct.name}` : ''}
                      {filters.status ? ` · Status: ${filters.status}` : ''}
                      {filters.effectiveOn ? ` · Effective: ${formatDate(filters.effectiveOn)}` : ''}
                    </span>
                  </p>
                )}
                <ul className="catalog-list" aria-label="Subscriptions">
                  {subscriptions.map((subscription) => (
                    <li className="catalog-card" key={subscription.id}>
                      <div className="catalog-card-primary">
                        <div>
                          <h3>Customer: {subscription.customerId}</h3>
                          <p>Product: {subscription.productId} · started {formatDate(subscription.startDate)}</p>
                        </div>
                        <span className={`status-pill customer-status ${subscription.status.toLowerCase()}`}>
                          {ownerSubscriptionStatusLabel(subscription.status)}
                        </span>
                      </div>
                      <dl className="catalog-values">
                        <div><dt>Default quantity</dt><dd>{subscription.defaultQuantity}</dd></div>
                        <div><dt>Start date</dt><dd>{formatDate(subscription.startDate)}</dd></div>
                        <div><dt>End date</dt><dd>{formatDateOrDash(subscription.endDate)}</dd></div>
                        <div><dt>Status</dt><dd>{ownerSubscriptionStatusLabel(subscription.status)}</dd></div>
                        <div><dt>Created</dt><dd>{formatDate(subscription.createdAt.slice(0, 10))}</dd></div>
                        <div><dt>Updated</dt><dd>{formatDate(subscription.updatedAt.slice(0, 10))}</dd></div>
                      </dl>
                      <div className="catalog-actions">
                        <button type="button" className="text-button" disabled>
                          View details
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>
          <footer className="page-footer"><span>Milk Web <b>·</b> Owner workspace</span><span>Subscription management</span></footer>
        </div>
      </main>
    </div>
  )
}
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { isUnauthenticatedError } from './authApi.ts'
import {
  createOwnerDailyRecord,
  getOwnerCustomers,
  getOwnerDailyRecords,
  getOwnerProducts,
  ownerDailyRecordErrorMessage,
  updateOwnerDailyRecord,
  type OwnerCustomerOption,
  type OwnerDailyRecord,
  type OwnerDailyRecordFilters,
  type OwnerDailyRecordPage,
  type OwnerDailyRecordSlot,
  type OwnerDailyRecordStatus,
  type OwnerProductOption,
} from './ownerDailyRecordsApi'

type OwnerDailyRecordsProps = {
  token: string
  onSignOut: () => void
  onSessionExpired: () => void
  onShowBilling: () => void
}

const statuses: { value: OwnerDailyRecordStatus; label: string }[] = [
  { value: 'COLLECTED', label: 'Collected' },
  { value: 'EXTRA', label: 'Extra milk' },
  { value: 'SKIPPED', label: 'Skipped' },
  { value: 'PAUSED', label: 'Paused' },
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

function formatAmount(value: number | null) {
  return value === null ? '—' : new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

function isChargeable(status: OwnerDailyRecordStatus) {
  return status === 'COLLECTED' || status === 'EXTRA'
}

function validateEntry(input: {
  customerId: string
  productId: string
  recordDate: string
  quantity: string
  unitPriceSnapshot: string
  status: OwnerDailyRecordStatus
  today: string
}) {
  if (!input.customerId || !input.productId) return 'Select a customer and product.'
  if (!input.recordDate || input.recordDate > input.today) return 'Choose today or a past collection date.'
  const quantity = Number(input.quantity)
  if (!Number.isFinite(quantity) || !/^\d{1,5}(\.\d{1,3})?$/.test(input.quantity)) {
    return 'Quantity must have up to 5 whole digits and 3 decimal places.'
  }
  if (isChargeable(input.status)) {
    if (quantity <= 0) return 'Collected and extra records need a quantity greater than zero.'
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(input.unitPriceSnapshot)
      || !Number.isFinite(Number(input.unitPriceSnapshot))) {
      return 'Enter a unit-price snapshot with up to 10 whole digits and 2 decimal places.'
    }
    if (Number(input.unitPriceSnapshot) < 0) return 'Unit-price snapshot cannot be negative.'
  } else if (quantity !== 0) {
    return 'Skipped and paused records must have zero quantity.'
  }
  return ''
}

export default function OwnerDailyRecords({
  token,
  onSignOut,
  onSessionExpired,
  onShowBilling,
}: OwnerDailyRecordsProps) {
  const today = todayInIndia()
  const [customers, setCustomers] = useState<OwnerCustomerOption[]>([])
  const [products, setProducts] = useState<OwnerProductOption[]>([])
  const [customersLoading, setCustomersLoading] = useState(true)
  const [productsLoading, setProductsLoading] = useState(true)
  const [customersError, setCustomersError] = useState('')
  const [productsError, setProductsError] = useState('')
  const [recordDate, setRecordDate] = useState(today)
  const [customerId, setCustomerId] = useState('')
  const [productId, setProductId] = useState('')
  const [slot, setSlot] = useState<OwnerDailyRecordSlot>('MORNING')
  const [status, setStatus] = useState<OwnerDailyRecordStatus>('COLLECTED')
  const [quantity, setQuantity] = useState('1')
  const [unitPriceSnapshot, setUnitPriceSnapshot] = useState('')
  const [entryError, setEntryError] = useState('')
  const [notice, setNotice] = useState('')
  const [saving, setSaving] = useState(false)
  const [editing, setEditing] = useState<OwnerDailyRecord | null>(null)
  const [filters, setFilters] = useState<OwnerDailyRecordFilters>({
    recordDate: today,
    customerId: '',
    productId: '',
    slot: '',
    status: '',
    page: 0,
  })
  const [records, setRecords] = useState<OwnerDailyRecordPage | null>(null)
  const [recordsLoading, setRecordsLoading] = useState(false)
  const [recordsError, setRecordsError] = useState('')
  const [filterDraft, setFilterDraft] = useState(filters)
  const customersRequestId = useRef(0)
  const productsRequestId = useRef(0)
  const recordsRequestId = useRef(0)
  const referencesLoading = customersLoading || productsLoading

  const activeProduct = products.find((product) => product.id === productId)
  const customerNames = useMemo(
    () => new Map(customers.map((customer) => [customer.id, customer.name])),
    [customers],
  )
  const productNames = useMemo(
    () => new Map(products.map((product) => [product.id, product])),
    [products],
  )

  const handleError = useCallback((error: unknown) => {
    if (isUnauthenticatedError(error)) onSessionExpired()
    return ownerDailyRecordErrorMessage(error)
  }, [onSessionExpired])

  const loadCustomers = useCallback(async () => {
    const request = ++customersRequestId.current
    setCustomersLoading(true)
    setCustomersError('')
    try {
      const customerResults = await getOwnerCustomers(token)
      if (request === customersRequestId.current) setCustomers(customerResults)
    } catch (error) {
      if (request === customersRequestId.current) setCustomersError(handleError(error))
    } finally {
      if (request === customersRequestId.current) setCustomersLoading(false)
    }
  }, [handleError, token])

  const loadProducts = useCallback(async () => {
    const request = ++productsRequestId.current
    setProductsLoading(true)
    setProductsError('')
    try {
      const productResults = await getOwnerProducts(token)
      if (request === productsRequestId.current) setProducts(productResults)
    } catch (error) {
      if (request === productsRequestId.current) setProductsError(handleError(error))
    } finally {
      if (request === productsRequestId.current) setProductsLoading(false)
    }
  }, [handleError, token])

  const loadRecords = useCallback(async (query: OwnerDailyRecordFilters = filters) => {
    const request = ++recordsRequestId.current
    setRecordsLoading(true)
    setRecordsError('')
    try {
      const result = await getOwnerDailyRecords(token, query)
      if (request === recordsRequestId.current) setRecords(result)
    } catch (error) {
      if (request === recordsRequestId.current) setRecordsError(handleError(error))
    } finally {
      if (request === recordsRequestId.current) setRecordsLoading(false)
    }
  }, [filters, handleError, token])

  useEffect(() => {
    void loadCustomers()
    void loadProducts()
    return () => {
      customersRequestId.current += 1
      productsRequestId.current += 1
    }
  }, [loadCustomers, loadProducts])

  useEffect(() => {
    void loadRecords(filters)
    return () => {
      recordsRequestId.current += 1
    }
  }, [filters, loadRecords])

  function changeStatus(nextStatus: OwnerDailyRecordStatus) {
    setStatus(nextStatus)
    setEntryError('')
    if (isChargeable(nextStatus)) {
      setQuantity((value) => Number(value) > 0 ? value : '1')
      if (!unitPriceSnapshot && activeProduct) setUnitPriceSnapshot(String(activeProduct.price))
    } else {
      setQuantity('0')
      if (!editing?.unitPriceSnapshot) setUnitPriceSnapshot('')
    }
  }

  function changeProduct(nextProductId: string) {
    setProductId(nextProductId)
    const nextProduct = products.find((product) => product.id === nextProductId)
    if (!editing && isChargeable(status) && nextProduct) {
      setUnitPriceSnapshot(String(nextProduct.price))
    }
  }

  function beginEdit(record: OwnerDailyRecord) {
    setEditing(record)
    setCustomerId(record.customerId)
    setProductId(record.productId)
    setRecordDate(record.recordDate)
    setSlot(record.slot)
    setStatus(record.status)
    setQuantity(String(record.quantity))
    setUnitPriceSnapshot(record.unitPriceSnapshot === null ? '' : String(record.unitPriceSnapshot))
    setEntryError('')
    setNotice('')
  }

  function cancelEdit() {
    setEditing(null)
    setEntryError('')
    setQuantity('1')
    setStatus('COLLECTED')
    setUnitPriceSnapshot(activeProduct ? String(activeProduct.price) : '')
  }

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!filterDraft.recordDate || filterDraft.recordDate > today) {
      setRecordsError('Choose today or a past date to review records.')
      return
    }
    setRecordsError('')
    const nextFilters = { ...filterDraft, page: 0 }
    setFilters(nextFilters)
    setFilterDraft(nextFilters)
  }

  async function saveRecord(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setEntryError('')
    setNotice('')
    const validationMessage = validateEntry({
      customerId,
      productId,
      recordDate,
      quantity,
      unitPriceSnapshot,
      status,
      today,
    })
    if (validationMessage) {
      setEntryError(validationMessage)
      return
    }
    if (editing && editing.recordDate !== today) {
      setEntryError('Only today’s daily records can be corrected.')
      return
    }
    const payload = {
      quantity: isChargeable(status) ? Number(quantity) : 0,
      unitPriceSnapshot: isChargeable(status) ? Number(unitPriceSnapshot) : null,
      status,
    }
    setSaving(true)
    try {
      if (editing) {
        await updateOwnerDailyRecord(token, editing.id, payload)
        setNotice('Daily record updated.')
        setEditing(null)
        setQuantity('1')
        setStatus('COLLECTED')
        setUnitPriceSnapshot(activeProduct ? String(activeProduct.price) : '')
      } else {
        await createOwnerDailyRecord(token, {
          customerId,
          productId,
          recordDate,
          slot,
          ...payload,
        })
        setNotice('Daily record saved.')
        setQuantity('1')
        setStatus('COLLECTED')
        setUnitPriceSnapshot(activeProduct ? String(activeProduct.price) : '')
      }
      if (filters.recordDate === recordDate) {
        await loadRecords(filters)
      } else {
        const nextFilters = { ...filters, recordDate, page: 0 }
        setFilters(nextFilters)
        setFilterDraft(nextFilters)
      }
    } catch (error) {
      setEntryError(handleError(error))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="app-shell owner-daily-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Milk Web home">
          <span className="brand-mark">m</span><span>milk<span className="brand-light">web</span></span>
        </a>
        <div className="workspace-label">WORKSPACE</div>
        <nav aria-label="Main navigation">
          <button type="button" className="nav-item owner-nav-item" onClick={onShowBilling}>
            <span className="nav-icon" aria-hidden="true">▤</span> Billing
          </button>
          <button type="button" className="nav-item owner-nav-item active" aria-current="page">
            <span className="nav-icon" aria-hidden="true">◷</span> Daily records
          </button>
          <span className="nav-item disabled"><span className="nav-icon">♧</span> Customers</span>
        </nav>
        <div className="sidebar-bottom">
          <div className="secure-note"><span className="secure-dot" /> Secure owner session</div>
          <button className="signout-button" onClick={onSignOut}>Sign out</button>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumb">Workspace <span>/</span> Daily records</div>
          <div className="topbar-right"><span className="today-label">{formatDate(today)}</span><span className="avatar">OW</span></div>
        </header>
        <div className="page-content owner-daily-records">
          <div className="page-heading">
            <div>
              <p className="eyebrow">ACTUAL COLLECTIONS</p>
              <h1>Daily records</h1>
              <p className="page-subtitle">Record what was actually collected for each customer and collection slot.</p>
            </div>
          </div>

          {notice && <p className="alert success-alert" role="status">{notice}</p>}
          <section className="daily-entry-card" aria-labelledby="daily-entry-title">
            <div className="section-heading">
              <div><p className="eyebrow">COLLECTION ENTRY</p><h2 id="daily-entry-title">{editing ? 'Correct today’s record' : 'Record a collection'}</h2></div>
              {editing && <button type="button" className="text-button" onClick={cancelEdit}>Cancel correction</button>}
            </div>
            {referencesLoading ? (
              <div className="customer-state" role="status">
                {customersLoading && productsLoading
                  ? 'Loading customers and products…'
                  : customersLoading
                    ? 'Loading customers…'
                    : 'Loading products…'}
              </div>
            ) : customersError || productsError ? (
              <section className="customer-state customer-error">
                <h2>We could not load customers and products</h2>
                {customersError && <p className="alert error-alert" role="alert">{customersError}</p>}
                {productsError && <p className="alert error-alert" role="alert">{productsError}</p>}
                <button className="secondary-button" onClick={() => {
                  if (customersError) void loadCustomers()
                  if (productsError) void loadProducts()
                }}>Try again</button>
              </section>
            ) : (
              <>
                <form className="daily-entry-form" aria-label="Daily record entry" onSubmit={(event) => void saveRecord(event)} noValidate>
                  <label htmlFor="daily-record-customer">Customer
                    <select id="daily-record-customer" value={customerId} onChange={(event) => setCustomerId(event.target.value)} disabled={Boolean(editing)} required>
                      <option value="">Select a customer</option>
                      {customers.map((customer) => (
                        <option key={customer.id} value={customer.id}>
                          {customer.name}{customer.phone ? ` · ${customer.phone}` : ''}{customer.status !== 'ACTIVE' ? ` · ${customer.status.toLowerCase()}` : ''}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label htmlFor="daily-record-product">Product
                    <select id="daily-record-product" value={productId} onChange={(event) => changeProduct(event.target.value)} disabled={Boolean(editing)} required>
                      <option value="">Select a product</option>
                      {products.map((product) => (
                        <option key={product.id} value={product.id}>
                          {product.name}{product.status !== 'ACTIVE' ? ' · inactive' : ''}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label htmlFor="daily-record-date">Collection date
                    <input id="daily-record-date" type="date" value={recordDate} max={today} onChange={(event) => setRecordDate(event.target.value)} disabled={Boolean(editing)} required />
                  </label>
                  <fieldset className="daily-slot-choice">
                    <legend>Collection slot</legend>
                    {(['MORNING', 'EVENING'] as const).map((value) => (
                      <label key={value} className={slot === value ? 'selected' : ''}>
                        <input type="radio" name="collection-slot" value={value} checked={slot === value} onChange={() => setSlot(value)} disabled={Boolean(editing)} />
                        {value === 'MORNING' ? 'Morning' : 'Evening'}
                      </label>
                    ))}
                  </fieldset>
                  <label htmlFor="daily-record-status">Status
                    <select id="daily-record-status" value={status} onChange={(event) => changeStatus(event.target.value as OwnerDailyRecordStatus)}>
                      {statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                    </select>
                  </label>
                  <label htmlFor="daily-record-quantity">Actual quantity{activeProduct ? ` (${activeProduct.unit === 'LITRE' ? 'L' : 'kg'})` : ''}
                    <input id="daily-record-quantity" inputMode="decimal" type="number" min={isChargeable(status) ? '0.001' : '0'} step="0.001"
                      value={quantity} onChange={(event) => setQuantity(event.target.value)} disabled={!isChargeable(status)} />
                  </label>
                  <div className="daily-field">
                    <label htmlFor="daily-record-price">Unit-price snapshot</label>
                    <input id="daily-record-price" inputMode="decimal" type="number" min="0" step="0.01" value={unitPriceSnapshot}
                      onChange={(event) => setUnitPriceSnapshot(event.target.value)}
                      disabled={!isChargeable(status) || Boolean(editing && isChargeable(editing.status))}
                      aria-describedby="daily-price-help" />
                    <span id="daily-price-help" className="form-hint">
                      {editing && isChargeable(editing.status)
                        ? 'The original price snapshot cannot be changed.'
                        : 'Prefilled from the current product price; confirm the applicable price for this date.'}
                    </span>
                  </div>
                  <div className="daily-entry-submit">
                    {entryError && <p className="alert error-alert" role="alert">{entryError}</p>}
                    <button className="primary-button" disabled={saving || customers.length === 0 || products.length === 0} aria-busy={saving}>
                      {saving ? 'Saving…' : editing ? 'Save correction' : 'Save daily record'}
                    </button>
                  </div>
                </form>
                {customers.length === 0 || products.length === 0 ? (
                  <p className="alert error-alert" role="status">
                    {customers.length === 0 && products.length === 0
                      ? 'Add a customer and a product before recording collections.'
                      : customers.length === 0
                        ? 'Add a customer before recording collections.'
                        : 'Add a product before recording collections.'}
                  </p>
                ) : null}
                <p className="customer-readonly-note">
                  Collected and extra records require a positive quantity and price snapshot. Skipped and paused records use zero quantity and no price. Amounts are calculated by the server.
                  Corrections are limited to today by the current backend rules; past records can be reviewed but not updated.
                </p>
              </>
            )}
          </section>

          <section className="daily-review" aria-labelledby="daily-review-title">
                <div className="section-heading">
                  <div><p className="eyebrow">RECORD REVIEW</p><h2 id="daily-review-title">Recorded collections</h2></div>
                  <button className="text-button" onClick={() => void loadRecords(filters)} disabled={recordsLoading}>
                    {recordsLoading ? 'Refreshing…' : 'Refresh'}
                  </button>
                </div>
                <form className="daily-record-filters" aria-label="Filter daily records" onSubmit={applyFilters}>
                  <label htmlFor="daily-record-filter-date">Date
                    <input id="daily-record-filter-date" type="date" value={filterDraft.recordDate} max={today}
                      onChange={(event) => setFilterDraft({ ...filterDraft, recordDate: event.target.value })} required />
                  </label>
                  <label htmlFor="daily-record-filter-customer">Customer
                    <select id="daily-record-filter-customer" value={filterDraft.customerId}
                      onChange={(event) => setFilterDraft({ ...filterDraft, customerId: event.target.value })}>
                      <option value="">All customers</option>
                      {customers.map((customer) => <option key={customer.id} value={customer.id}>{customer.name}</option>)}
                    </select>
                  </label>
                  <label htmlFor="daily-record-filter-product">Product
                    <select id="daily-record-filter-product" value={filterDraft.productId}
                      onChange={(event) => setFilterDraft({ ...filterDraft, productId: event.target.value })}>
                      <option value="">All products</option>
                      {products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}
                    </select>
                  </label>
                  <label htmlFor="daily-record-filter-slot">Slot
                    <select id="daily-record-filter-slot" value={filterDraft.slot}
                      onChange={(event) => setFilterDraft({ ...filterDraft, slot: event.target.value as OwnerDailyRecordFilters['slot'] })}>
                      <option value="">Morning and evening</option><option value="MORNING">Morning</option><option value="EVENING">Evening</option>
                    </select>
                  </label>
                  <label htmlFor="daily-record-filter-status">Status
                    <select id="daily-record-filter-status" value={filterDraft.status}
                      onChange={(event) => setFilterDraft({ ...filterDraft, status: event.target.value as OwnerDailyRecordFilters['status'] })}>
                      <option value="">All statuses</option>
                      {statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                    </select>
                  </label>
                  <button className="secondary-button" type="submit">Apply filters</button>
                </form>

                {recordsError && (
                  <section className="customer-state customer-error">
                    <p className="alert error-alert" role="alert">{recordsError}</p>
                    <button className="secondary-button" onClick={() => void loadRecords(filters)}>Try again</button>
                  </section>
                )}
                {recordsLoading ? (
                  <div className="customer-state" role="status">Loading daily records…</div>
                ) : !recordsError && records?.content.length === 0 ? (
                  <div className="customer-state"><h3>No records for this date</h3><p>Choose another date or adjust the filters.</p></div>
                ) : !recordsError && records ? (
                  <>
                    <ul className="owner-record-list" aria-label="Recorded collections">
                      {records.content.map((record) => {
                        const product = productNames.get(record.productId)
                        const mayEdit = record.recordDate === today
                        return (
                          <li className="owner-record-card" key={record.id}>
                            <div className="owner-record-primary">
                              <div>
                                <h3>{customerNames.get(record.customerId) ?? 'Customer'}</h3>
                                <p>{product?.name ?? 'Product'} · {formatDate(record.recordDate)} · {record.slot === 'MORNING' ? 'Morning' : 'Evening'}</p>
                              </div>
                              <span className={`status-pill record-status ${record.status.toLowerCase()}`}>
                                {statuses.find((item) => item.value === record.status)?.label}
                              </span>
                            </div>
                            <dl className="owner-record-values">
                              <div><dt>Quantity</dt><dd>{record.quantity} {product?.unit === 'LITRE' ? 'L' : product?.unit === 'KILOGRAM' ? 'kg' : ''}</dd></div>
                              <div><dt>Unit-price snapshot</dt><dd>{formatAmount(record.unitPriceSnapshot)}</dd></div>
                              <div><dt>Amount</dt><dd>{formatAmount(record.amount)}</dd></div>
                            </dl>
                            {mayEdit ? (
                              <button type="button" className="text-button owner-record-edit" onClick={() => beginEdit(record)}>Correct today’s record</button>
                            ) : <span className="action-hint">Past record · read-only</span>}
                          </li>
                        )
                      })}
                    </ul>
                    <div className="records-pagination">
                      <span>{records.totalElements} records</span>
                      <div>
                        <button className="secondary-button" disabled={records.number <= 0 || recordsLoading}
                          onClick={() => setFilters({ ...filters, page: Math.max(0, (filters.page ?? 0) - 1) })}>Previous</button>
                        <span>Page {records.number + 1} of {Math.max(1, records.totalPages)}</span>
                        <button className="secondary-button" disabled={records.number + 1 >= records.totalPages || recordsLoading}
                          onClick={() => setFilters({ ...filters, page: (filters.page ?? 0) + 1 })}>Next</button>
                      </div>
                    </div>
                  </>
                ) : null}
              </section>
          <footer className="page-footer"><span>Milk Web <b>·</b> Owner workspace</span><span>Daily collection records</span></footer>
        </div>
      </main>
    </div>
  )
}

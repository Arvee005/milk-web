import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { isUnauthenticatedError } from './authApi.ts'
import {
  createOwnerProduct,
  formatProductPrice,
  getOwnerProducts,
  ownerProductErrorMessage,
  productStatusLabel,
  productUnitLabel,
  updateOwnerProduct,
  type OwnerProduct,
  type OwnerProductStatus,
  type OwnerProductUnit,
} from './ownerProductsApi'

type OwnerProductsProps = {
  token: string
  onSignOut: () => void
  onSessionExpired: () => void
  onShowBilling: () => void
  onShowDailyRecords: () => void
  onShowCustomers: () => void
  onShowSubscriptions: () => void
}

const units: { value: OwnerProductUnit; label: string }[] = [
  { value: 'LITRE', label: 'Litre' },
  { value: 'KILOGRAM', label: 'Kilogram' },
]

const statuses: { value: OwnerProductStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
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

// Mirrors the server contract: a name of at most 100 characters, a description of
// at most 500, and a non-negative price of at most 10 whole digits and 2 decimals.
function validateProduct(input: { name: string; description: string; price: string }) {
  if (!input.name.trim()) return 'Enter the product name.'
  if (input.name.trim().length > 100) return 'Product name must be 100 characters or fewer.'
  if (input.description.trim().length > 500) return 'Description must be 500 characters or fewer.'
  if (!/^\d{1,10}(\.\d{1,2})?$/.test(input.price) || !Number.isFinite(Number(input.price))) {
    return 'Enter a price with up to 10 whole digits and 2 decimal places.'
  }
  if (Number(input.price) < 0) return 'Price cannot be negative.'
  return ''
}

export default function OwnerProducts({
  token,
  onSignOut,
  onSessionExpired,
  onShowBilling,
  onShowDailyRecords,
  onShowCustomers,
  onShowSubscriptions,
}: OwnerProductsProps) {
  const today = todayInIndia()
  const [products, setProducts] = useState<OwnerProduct[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [unit, setUnit] = useState<OwnerProductUnit>('LITRE')
  const [price, setPrice] = useState('')
  const [status, setStatus] = useState<OwnerProductStatus>('ACTIVE')
  const [entryError, setEntryError] = useState('')
  const [notice, setNotice] = useState('')
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const [editing, setEditing] = useState<OwnerProduct | null>(null)
  const requestId = useRef(0)

  const handleError = useCallback((error: unknown) => {
    if (isUnauthenticatedError(error)) onSessionExpired()
    return ownerProductErrorMessage(error)
  }, [onSessionExpired])

  const loadProducts = useCallback(async () => {
    const request = ++requestId.current
    setLoading(true)
    setLoadError('')
    try {
      const results = await getOwnerProducts(token)
      if (request === requestId.current) setProducts(results)
    } catch (error) {
      if (request === requestId.current) setLoadError(handleError(error))
    } finally {
      if (request === requestId.current) setLoading(false)
    }
  }, [handleError, token])

  useEffect(() => {
    void loadProducts()
    return () => {
      requestId.current += 1
    }
  }, [loadProducts])

  function resetForm() {
    setName('')
    setDescription('')
    setUnit('LITRE')
    setPrice('')
    setStatus('ACTIVE')
    setEditing(null)
    setEntryError('')
  }

  function beginEdit(product: OwnerProduct) {
    setEditing(product)
    setName(product.name)
    setDescription(product.description ?? '')
    setUnit(product.unit)
    setPrice(String(product.price))
    setStatus(product.status)
    setEntryError('')
    setNotice('')
  }

  // A rename or unit change rewrites the label that historical customer-facing
  // records display, because those responses resolve the product live.
  function identitySignature(product: OwnerProduct, nextName: string, nextUnit: OwnerProductUnit) {
    if (nextName.trim() === product.name && nextUnit === product.unit) return ''
    return `${nextName.trim()}|${nextUnit}`
  }

  async function saveProduct(event?: FormEvent<HTMLFormElement>, acknowledgedOverride?: string) {
    event?.preventDefault()
    if (savingRef.current) return
    setEntryError('')
    setNotice('')
    const validationMessage = validateProduct({ name, description, price })
    if (validationMessage) {
      setEntryError(validationMessage)
      return
    }
    const signature = editing ? identitySignature(editing, name, unit) : ''
    const acknowledged = acknowledgedOverride ?? ''
    if (signature && signature !== acknowledged) return
    savingRef.current = true
    setSaving(true)
    try {
      if (editing) {
        await updateOwnerProduct(token, editing.id, {
          name: name.trim(),
          description: description.trim() || null,
          unit,
          price: Number(price),
          status,
        })
        setNotice(`Product ${name.trim()} updated.`)
        resetForm()
      } else {
        await createOwnerProduct(token, {
          name: name.trim(),
          description: description.trim() || null,
          unit,
          price: Number(price),
        })
        setNotice(`Product ${name.trim()} created as active.`)
        resetForm()
      }
      await loadProducts()
    } catch (error) {
      setEntryError(handleError(error))
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  async function changeStatus(product: OwnerProduct, nextStatus: OwnerProductStatus) {
    if (savingRef.current) return
    setEntryError('')
    setNotice('')
    savingRef.current = true
    setSaving(true)
    try {
      await updateOwnerProduct(token, product.id, {
        name: product.name,
        description: product.description,
        unit: product.unit,
        price: product.price,
        status: nextStatus,
      })
      setNotice(`Product ${product.name} is now ${productStatusLabel(nextStatus).toLowerCase()}.`)
      await loadProducts()
    } catch (error) {
      setEntryError(handleError(error))
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  const pendingSignature = editing ? identitySignature(editing, name, unit) : ''
  const needsIdentityConfirmation = Boolean(pendingSignature)

  return (
    <div className="app-shell owner-catalog-shell">
      <aside className="sidebar">
        <a className="brand" href="/" aria-label="Radhika Farms home">
          <span className="brand-mark" aria-hidden="true">R</span><span>Radhika <span className="brand-light">Farms</span></span>
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
          <button type="button" className="nav-item owner-nav-item active" aria-current="page">
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
          <div className="breadcrumb">Workspace <span>/</span> Products</div>
          <div className="topbar-right"><span className="today-label">{formatDate(today)}</span><span className="avatar">OW</span></div>
        </header>
        <div className="page-content owner-catalog">
          <div className="page-heading">
            <div>
              <p className="eyebrow">PRODUCT CATALOG</p>
              <h1>Products</h1>
              <p className="page-subtitle">Maintain the products you sell and the current price used to pre-fill new collections.</p>
            </div>
          </div>

          {notice && <p className="alert success-alert" role="status">{notice}</p>}
          <section className="catalog-entry-card" aria-labelledby="product-entry-title">
            <div className="section-heading">
              <div><p className="eyebrow">{editing ? 'EDIT PRODUCT' : 'NEW PRODUCT'}</p><h2 id="product-entry-title">{editing ? `Edit ${editing.name}` : 'Add a product'}</h2></div>
              {editing && <button type="button" className="text-button" onClick={resetForm}>Cancel edit</button>}
            </div>
            {loading ? (
              <div className="customer-state" role="status">Loading products…</div>
            ) : loadError ? (
              <section className="customer-state customer-error">
                <h2>We could not load products</h2>
                <p className="alert error-alert" role="alert">{loadError}</p>
                <button className="secondary-button" onClick={() => void loadProducts()}>Try again</button>
              </section>
            ) : (
              <>
                <form className="catalog-form" aria-label="Product details" onSubmit={(event) => void saveProduct(event)} noValidate>
                  <label htmlFor="product-name">Name
                    <input id="product-name" type="text" maxLength={100} value={name} onChange={(event) => setName(event.target.value)} required />
                  </label>
                  <label htmlFor="product-unit">Unit
                    <select id="product-unit" value={unit} onChange={(event) => setUnit(event.target.value as OwnerProductUnit)}>
                      {units.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                    </select>
                  </label>
                  <div className="catalog-field">
                    <label htmlFor="product-price">Price per unit</label>
                    <input id="product-price" type="text" inputMode="decimal" value={price} onChange={(event) => setPrice(event.target.value)}
                      aria-describedby="product-price-help" required />
                    <span id="product-price-help" className="form-hint">
                      A new product is always created as active. Changing this price affects future collections only; historical daily records and issued bills keep their stored price snapshot.
                    </span>
                  </div>
                  <label htmlFor="product-description">Description
                    <input id="product-description" type="text" maxLength={500} value={description} onChange={(event) => setDescription(event.target.value)} />
                  </label>
                  {editing && (
                    <label htmlFor="product-status">Status
                      <select id="product-status" value={status} onChange={(event) => setStatus(event.target.value as OwnerProductStatus)}>
                        {statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                      </select>
                    </label>
                  )}
                  <div className="catalog-submit">
                    {entryError && <p className="alert error-alert" role="alert">{entryError}</p>}
                    <button className="primary-button" disabled={saving} aria-busy={saving}>
                      {saving ? 'Saving…' : editing ? 'Save product' : 'Create product'}
                    </button>
                  </div>
                </form>
                {editing && pendingSignature && (
                  <div className="catalog-warning" role="alert">
                    <p className="catalog-warning-title">This changes how historical records are labelled</p>
                    <p>
                      Renaming <b>{editing.name}</b> to <b>{name.trim() || '(blank)'}</b> or changing its unit to <b>{productUnitLabel(unit)}</b>
                      also changes the name and unit shown on records the customer has already collected. Recorded quantities, price snapshots,
                      amounts, and issued bills are not affected.
                    </p>
                    {needsIdentityConfirmation ? (
                      <button type="button" className="secondary-button"
                        onClick={() => void saveProduct(undefined, pendingSignature)}>
                        Confirm and save
                      </button>
                    ) : (
                      <p className="form-hint">Confirmed. Save the product to apply the change.</p>
                    )}
                  </div>
                )}
                <p className="customer-readonly-note">
                  Products are never deleted. Set a product to inactive to stop offering it; existing records and bills keep their stored product, price, and amount.
                </p>
              </>
            )}
          </section>

          <section className="catalog-review" aria-labelledby="product-review-title">
            <div className="section-heading">
              <div><p className="eyebrow">CATALOG</p><h2 id="product-review-title">All products</h2></div>
              <button type="button" className="text-button" onClick={() => void loadProducts()} disabled={loading}>
                {loading ? 'Refreshing…' : 'Refresh'}
              </button>
            </div>

            {!loading && !loadError && products.length === 0 ? (
              <div className="customer-state"><h3>No products yet</h3><p>Add the first product using the form above.</p></div>
            ) : !loadError && products.length > 0 ? (
              <ul className="catalog-list" aria-label="Products">
                {products.map((product) => (
                  <li className="catalog-card" key={product.id}>
                    <div className="catalog-card-primary">
                      <div>
                        <h3>{product.name}</h3>
                        <p>{productUnitLabel(product.unit)} · updated {formatDate(product.updatedAt.slice(0, 10))}</p>
                      </div>
                      <span className={`status-pill customer-status ${product.status.toLowerCase()}`}>
                        {productStatusLabel(product.status)}
                      </span>
                    </div>
                    <dl className="catalog-values">
                      <div><dt>Price per {product.unit === 'LITRE' ? 'litre' : 'kg'}</dt><dd>{formatProductPrice(product.price)}</dd></div>
                      <div><dt>Description</dt><dd>{product.description ?? '—'}</dd></div>
                    </dl>
                    <div className="catalog-actions">
                      <button type="button" className="text-button" onClick={() => beginEdit(product)}>Edit product</button>
                      {product.status === 'ACTIVE' ? (
                        <button type="button" className="text-button" disabled={saving}
                          onClick={() => void changeStatus(product, 'INACTIVE')}>Deactivate</button>
                      ) : (
                        <button type="button" className="text-button" disabled={saving}
                          onClick={() => void changeStatus(product, 'ACTIVE')}>Activate</button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            ) : null}
          </section>
          <footer className="page-footer"><span>Radhika Farms <b>·</b> Owner workspace</span><span>Product catalog</span></footer>
        </div>
      </main>
    </div>
  )
}

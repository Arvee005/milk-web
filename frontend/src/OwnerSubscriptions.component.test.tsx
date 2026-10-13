import assert from 'node:assert/strict'
import { after, afterEach, describe, it } from 'node:test'
import { JSDOM } from 'jsdom'
import { createElement } from 'react'
import OwnerSubscriptions from './OwnerSubscriptions.tsx'

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'http://localhost/',
})
Object.assign(globalThis, {
  window: dom.window,
  document: dom.window.document,
  HTMLElement: dom.window.HTMLElement,
  IS_REACT_ACT_ENVIRONMENT: true,
})
Object.defineProperty(globalThis, 'navigator', {
  configurable: true,
  value: dom.window.navigator,
})

const { cleanup, fireEvent, render, screen, waitFor, within } = await import('@testing-library/react')
const originalFetch = globalThis.fetch

function customer(overrides: Record<string, unknown> = {}) {
  return {
    id: 'customer-1',
    userId: 'user-1',
    phone: '9400000000',
    name: 'Test Customer',
    address: '123 Test St',
    billingType: 'MONTHLY',
    status: 'ACTIVE',
    joinDate: '2026-08-01',
    ...overrides,
  }
}

function product(overrides: Record<string, unknown> = {}) {
  return {
    id: 'product-1',
    name: 'Cow milk',
    description: 'Whole milk',
    unit: 'LITRE',
    price: 60,
    status: 'ACTIVE',
    createdAt: '2026-08-01T04:00:00Z',
    updatedAt: '2026-08-01T04:00:00Z',
    ...overrides,
  }
}

function subscription(overrides: Record<string, unknown> = {}) {
  return {
    id: 'subscription-1',
    customerId: 'customer-1',
    productId: 'product-1',
    defaultQuantity: 1.5,
    status: 'ACTIVE',
    startDate: '2026-10-01',
    endDate: null,
    createdAt: '2026-08-01T04:00:00Z',
    updatedAt: '2026-08-01T04:00:00Z',
    ...overrides,
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createFetcher(
  subscriptions: ReturnType<typeof subscription>[] = [subscription()],
  customers: ReturnType<typeof customer>[] = [customer()],
  products: ReturnType<typeof product>[] = [product()],
  subscriptionError = false,
  customerError = false,
  productError = false,
  subscriptionStatus = 200,
  customerStatus = 200,
  productStatus = 200,
): typeof fetch {
  let subscriptionCallCount = 0
  let customerCallCount = 0
  let productCallCount = 0

  return async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = String(input)
    if (url.includes('/owner/subscriptions')) {
      subscriptionCallCount += 1
      if (subscriptionError && subscriptionCallCount === 1) {
        return jsonResponse({ error: 'Could not load subscriptions.' }, subscriptionStatus)
      }
      return jsonResponse(subscriptions, subscriptionStatus)
    }
    if (url.includes('/owner/customers')) {
      customerCallCount += 1
      if (customerError && customerCallCount === 1) {
        return jsonResponse({ error: 'Could not load customers.' }, customerStatus)
      }
      return jsonResponse(customers, customerStatus)
    }
    if (url.includes('/owner/products')) {
      productCallCount += 1
      if (productError && productCallCount === 1) {
        return jsonResponse({ error: 'Could not load products.' }, productStatus)
      }
      return jsonResponse(products, productStatus)
    }
    return jsonResponse({ error: 'Not found' }, 404)
  }
}

async function showSubscriptions(
  fetcher: typeof fetch,
  onSessionExpired = () => undefined,
) {
  globalThis.fetch = fetcher
  render(createElement(OwnerSubscriptions, {
    token: 'synthetic-owner-token',
    onSignOut: () => undefined,
    onSessionExpired,
    onShowBilling: () => undefined,
    onShowDailyRecords: () => undefined,
    onShowCustomers: () => undefined,
    onShowProducts: () => undefined,
  }))
}

function filterForm() {
  return within(screen.getByRole('form', { name: 'Filter subscriptions' }))
}

function getFilterSelect(label: string) {
  return filterForm().getByLabelText(label) as HTMLSelectElement
}

function getFilterInput(label: string) {
  return filterForm().getByLabelText(label) as HTMLInputElement
}

function createForm() {
  return screen.getByRole('form', { name: 'Subscription details' })
}

function getCreateSelect(label: string) {
  return within(createForm()).getByLabelText((content) => content.includes(label)) as HTMLSelectElement
}

function getCreateInput(label: string) {
  return within(createForm()).getByLabelText((content) => content.includes(label)) as HTMLInputElement
}


afterEach(() => {
  cleanup()
  globalThis.fetch = originalFetch
})

after(() => dom.window.close())

describe('owner subscriptions React workflow', { concurrency: false }, () => {
  it('renders all filters: customer, product, status, effectiveOn', async () => {
    const fetcher = createFetcher([subscription()], [customer()], [product()])
    await showSubscriptions(fetcher)
    await screen.findByText(/Customer: customer-1/)

    assert.ok(screen.getByLabelText('Customer'))
    assert.ok(screen.getByLabelText('Product'))
    assert.ok(screen.getByLabelText('Status'))
    assert.ok(screen.getByLabelText('Effective on'))
  })

  it('customer filter sends customerId in query', async () => {
    let capturedUrl = ''
    const fetcher = createFetcher(
      [subscription({ customerId: 'customer-2' })],
      [customer(), customer({ id: 'customer-2', name: 'Second Customer' })],
      [product()],
    )
    const customFetcher = async (input: RequestInfo | URL, init?: RequestInit) => {
      capturedUrl = String(input)
      return fetcher(input, init)
    }
    await showSubscriptions(customFetcher)
    await screen.findByRole('listitem')

    const customerSelect = getFilterSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-2' } })

    await screen.findByRole('listitem')
    assert.match(capturedUrl, /customerId=customer-2/)
  })

  it('product filter sends productId in query', async () => {
    let capturedUrl = ''
    const fetcher = createFetcher(
      [subscription({ productId: 'product-2' })],
      [customer()],
      [product(), product({ id: 'product-2', name: 'Buffalo milk' })],
    )
    const customFetcher = async (input: RequestInfo | URL, init?: RequestInit) => {
      capturedUrl = String(input)
      return fetcher(input, init)
    }
    await showSubscriptions(customFetcher)
    await screen.findByText(/Customer: customer-1/)

    const productSelect = getFilterSelect('Product')
    fireEvent.change(productSelect, { target: { value: 'product-2' } })

    await screen.findByText(/Customer: customer-1/)
    assert.match(capturedUrl, /productId=product-2/)
  })

  it('status filter sends status in query', async () => {
    let capturedUrl = ''
    const fetcher = createFetcher(
      [subscription({ status: 'PAUSED' })],
      [customer()],
      [product()],
    )
    const customFetcher = async (input: RequestInfo | URL, init?: RequestInit) => {
      capturedUrl = String(input)
      return fetcher(input, init)
    }
    await showSubscriptions(customFetcher)
    await screen.findByText(/Customer: customer-1/)

    const statusSelect = getFilterSelect('Status')
    fireEvent.change(statusSelect, { target: { value: 'PAUSED' } })

    await screen.findByText(/Customer: customer-1/)
    assert.match(capturedUrl, /status=PAUSED/)
  })

  it('effectiveOn filter sends effectiveOn in query', async () => {
    let capturedUrl = ''
    const fetcher = createFetcher(
      [subscription()],
      [customer()],
      [product()],
    )
    const customFetcher = async (input: RequestInfo | URL, _init?: RequestInit) => {
      capturedUrl = String(input)
      return fetcher(input, _init)
    }
    await showSubscriptions(customFetcher)
    await screen.findByText(/Customer: customer-1/)

    const effectiveInput = getFilterInput('Effective on')
    fireEvent.change(effectiveInput, { target: { value: '2026-10-15' } })

    await screen.findByText(/Customer: customer-1/)
    assert.match(capturedUrl, /effectiveOn=2026-10-15/)
  })

  it('clearing filters removes query parameters', async () => {
    let capturedUrl = ''
    const fetcher = createFetcher(
      [subscription()],
      [customer()],
      [product()],
    )
    const customFetcher = async (input: RequestInfo | URL, init?: RequestInit) => {
      capturedUrl = String(input)
      return fetcher(input, init)
    }
    await showSubscriptions(customFetcher)
    await screen.findByText(/Customer: customer-1/)

    const customerSelect = getFilterSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-1' } })
    await screen.findByText(/Customer: customer-1/)

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await screen.findByText(/Customer: customer-1/)

    assert.match(capturedUrl, /\/owner\/subscriptions$/)
    assert.doesNotMatch(capturedUrl, /customerId/)
  })

  it('changing filters reloads the subscription list', async () => {
    let loadCount = 0
    const fetcher = async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/owner/subscriptions')) {
        loadCount += 1
        if (url.includes('customerId=customer-2')) {
          return jsonResponse([subscription({ id: 'sub-2', customerId: 'customer-2' })])
        }
        return jsonResponse([subscription({ id: 'sub-1', customerId: 'customer-1' })])
      }
      return jsonResponse([customer({ id: 'customer-1' }), customer({ id: 'customer-2', name: 'Second Customer' })])
    }
    await showSubscriptions(fetcher)
    await screen.findByText(/Customer: customer-1/)

    assert.equal(loadCount, 1)

    const customerSelect = getFilterSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-2' } })
    await screen.findByText(/Customer: customer-2/)

    assert.equal(loadCount, 2)
  })

  it('filter request error shows error state and allows retry', async () => {
    let subscriptionCalls = 0
    const fetcher = async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/owner/subscriptions')) {
        subscriptionCalls += 1
        if (subscriptionCalls === 2) {
          return jsonResponse({ error: 'Could not load subscriptions.' }, 500)
        }
        return jsonResponse([subscription()])
      }
      if (url.includes('/owner/customers')) return jsonResponse([customer()])
      if (url.includes('/owner/products')) return jsonResponse([product()])
      return jsonResponse({ error: 'Not found' }, 404)
    }
    await showSubscriptions(fetcher)
    await screen.findByText((content) => content.includes('Customer: customer-1'))

    const customerSelect = getFilterSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-1' } })

    const reviewSection = screen.getByRole('region', { name: 'Subscriptions' })
    await within(reviewSection).findByRole('alert')
    assert.match(within(reviewSection).getByRole('alert').textContent ?? '', /Could not load subscriptions/)

    fireEvent.click(within(reviewSection).getByRole('button', { name: 'Try again' }))
    await screen.findByText((content) => content.includes('Customer: customer-1'))
    assert.equal(subscriptionCalls, 3)
  })

  it('401 on filter reload triggers session expiry', async () => {
    let sessionExpired = false
    let subscriptionCalls = 0
    const fetcher = async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/owner/subscriptions')) {
        subscriptionCalls += 1
        if (subscriptionCalls === 2) {
          return jsonResponse({ error: 'Unauthorized' }, 401)
        }
        return jsonResponse([subscription()])
      }
      if (url.includes('/owner/customers')) return jsonResponse([customer()])
      if (url.includes('/owner/products')) return jsonResponse([product()])
      return jsonResponse({ error: 'Not found' }, 404)
    }
    await showSubscriptions(fetcher, () => { sessionExpired = true })
    await screen.findByText(/Customer: customer-1/)

    const customerSelect = getFilterSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-1' } })

    await waitFor(() => {
      assert.equal(sessionExpired, true)
    })
  })

  it('403 on filter reload shows authorization error without session expiry', async () => {
    let sessionExpired = false
    let subscriptionCalls = 0
    const fetcher = async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/owner/subscriptions')) {
        subscriptionCalls += 1
        if (subscriptionCalls === 2) {
          return jsonResponse({ error: 'Forbidden.' }, 403)
        }
        return jsonResponse([subscription()])
      }
      if (url.includes('/owner/customers')) return jsonResponse([customer()])
      if (url.includes('/owner/products')) return jsonResponse([product()])
      return jsonResponse({ error: 'Not found' }, 404)
    }
    await showSubscriptions(fetcher, () => { sessionExpired = true })
    await screen.findByText(/Customer: customer-1/)

    const customerSelect = getFilterSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-1' } })

    const reviewSection = screen.getByRole('region', { name: 'Subscriptions' })
    await waitFor(() => {
      assert.match(within(reviewSection).getByRole('alert').textContent ?? '', /permission/)
    })
    assert.equal(sessionExpired, false)
  })

  it('create form renders', async () => {
    const fetcher = createFetcher([subscription()], [customer()], [product()])
    await showSubscriptions(fetcher)
    await screen.findByRole('listitem')

    assert.ok(getCreateSelect('Customer'))
    assert.ok(getCreateSelect('Product'))
    assert.ok(getCreateInput('Default quantity'))
    assert.ok(getCreateInput('Start date'))
    assert.ok(getCreateInput('End date'))
    assert.ok(screen.getByRole('button', { name: 'Create subscription' }))
  })

  it('customer and product selects show ACTIVE only', async () => {
    const fetcher = createFetcher(
      [subscription()],
      [
        customer({ id: 'c1', name: 'Active Customer', status: 'ACTIVE' }),
        customer({ id: 'c2', name: 'Paused Customer', status: 'PAUSED' }),
        customer({ id: 'c3', name: 'Inactive Customer', status: 'INACTIVE' }),
      ],
      [
        product({ id: 'p1', name: 'Active Product', status: 'ACTIVE' }),
        product({ id: 'p2', name: 'Inactive Product', status: 'INACTIVE' }),
      ],
    )
    await showSubscriptions(fetcher)
    await screen.findByRole('listitem')

    const customerSelect = getCreateSelect('Customer')
    const customerOptions = Array.from(customerSelect.options).map((o) => o.textContent ?? '')
    assert.ok(customerOptions.some((t) => t.includes('Active Customer')))
    assert.ok(!customerOptions.some((t) => t.includes('Paused Customer')))
    assert.ok(!customerOptions.some((t) => t.includes('Inactive Customer')))

    const productSelect = getCreateSelect('Product')
    const productOptions = Array.from(productSelect.options).map((o) => o.textContent ?? '')
    assert.ok(productOptions.some((t) => t.includes('Active Product')))
    assert.ok(!productOptions.some((t) => t.includes('Inactive Product')))
  })

  it('successful create calls API, shows feedback, resets form, refreshes list', async () => {
    let createCalls = 0
    let listLoadCalls = 0
    const fetcher = async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/owner/subscriptions') && init?.method === 'POST') {
        createCalls += 1
        return jsonResponse(subscription({ id: 'new-sub', customerId: 'customer-1', productId: 'product-1' }))
      }
      if (url.includes('/owner/subscriptions')) {
        listLoadCalls += 1
        if (listLoadCalls === 1) return jsonResponse([subscription()])
        return jsonResponse([subscription({ id: 'new-sub', customerId: 'customer-1', productId: 'product-1' })])
      }
      if (url.includes('/owner/customers')) return jsonResponse([customer()])
      if (url.includes('/owner/products')) return jsonResponse([product()])
      return jsonResponse({ error: 'Not found' }, 404)
    }
    await showSubscriptions(fetcher)
    await screen.findByRole('listitem')

    const customerSelect = getCreateSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-1' } })
    const productSelect = getCreateSelect('Product')
    fireEvent.change(productSelect, { target: { value: 'product-1' } })
    const quantityInput = getCreateInput('Default quantity')
    fireEvent.change(quantityInput, { target: { value: '2.5' } })
    const startDateInput = getCreateInput('Start date')
    fireEvent.change(startDateInput, { target: { value: '2026-10-15' } })
    const endDateInput = getCreateInput('End date')
    fireEvent.change(endDateInput, { target: { value: '' } })

    fireEvent.click(screen.getByRole('button', { name: 'Create subscription' }))

    assert.equal(createCalls, 1)

    await waitFor(() => assert.equal(customerSelect.value, ''))
    await waitFor(() => assert.equal(productSelect.value, ''))
    await waitFor(() => assert.equal(quantityInput.value, ''))
    await waitFor(() => assert.equal(endDateInput.value, ''))
    await waitFor(() => assert.ok(startDateInput.value.length > 0))

    await waitFor(() => assert.equal(listLoadCalls, 2))
  })

  it('successful create keeps success notice visible after form reset', async () => {
    let createCalls = 0
    let listLoadCalls = 0
    const fetcher = async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/owner/subscriptions') && init?.method === 'POST') {
        createCalls += 1
        return jsonResponse(subscription({ id: 'new-sub', customerId: 'customer-1', productId: 'product-1' }))
      }
      if (url.includes('/owner/subscriptions')) {
        listLoadCalls += 1
        if (listLoadCalls === 1) return jsonResponse([subscription()])
        return jsonResponse([subscription({ id: 'new-sub', customerId: 'customer-1', productId: 'product-1' })])
      }
      if (url.includes('/owner/customers')) return jsonResponse([customer()])
      if (url.includes('/owner/products')) return jsonResponse([product()])
      return jsonResponse({ error: 'Not found' }, 404)
    }
    await showSubscriptions(fetcher)
    await screen.findByRole('listitem')

    const customerSelect = getCreateSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-1' } })
    const productSelect = getCreateSelect('Product')
    fireEvent.change(productSelect, { target: { value: 'product-1' } })
    const quantityInput = getCreateInput('Default quantity')
    fireEvent.change(quantityInput, { target: { value: '2.5' } })
    const startDateInput = getCreateInput('Start date')
    fireEvent.change(startDateInput, { target: { value: '2026-10-15' } })

    fireEvent.click(screen.getByRole('button', { name: 'Create subscription' }))

    assert.equal(createCalls, 1)

    await waitFor(() => assert.equal(customerSelect.value, ''))
    await waitFor(() => assert.equal(productSelect.value, ''))
    await waitFor(() => assert.equal(quantityInput.value, ''))
    await waitFor(() => assert.equal(listLoadCalls, 2))

    await screen.findByText(/Subscription created\. It starts ACTIVE\./)
  })

  it('create payload matches contract exactly', async () => {
    let capturedBody: unknown = null
    const fetcher = async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/owner/subscriptions') && init?.method === 'POST') {
        const body = JSON.parse(init.body as string)
        capturedBody = body
        return jsonResponse(subscription({ id: 'new-sub', customerId: 'customer-1', productId: 'product-1' }))
      }
      if (url.includes('/owner/subscriptions')) return jsonResponse([subscription()])
      if (url.includes('/owner/customers')) return jsonResponse([customer()])
      if (url.includes('/owner/products')) return jsonResponse([product()])
      return jsonResponse({ error: 'Not found' }, 404)
    }
    await showSubscriptions(fetcher)
    await screen.findByRole('listitem')

    const customerSelect = getCreateSelect('Customer')
    fireEvent.change(customerSelect, { target: { value: 'customer-1' } })
    const productSelect = getCreateSelect('Product')
    fireEvent.change(productSelect, { target: { value: 'product-1' } })
    const quantityInput = getCreateInput('Default quantity')
    fireEvent.change(quantityInput, { target: { value: '1.5' } })
    const startDateInput = getCreateInput('Start date')
    fireEvent.change(startDateInput, { target: { value: '2026-10-01' } })
    const endDateInput = getCreateInput('End date')
    fireEvent.change(endDateInput, { target: { value: '' } })

    fireEvent.click(screen.getByRole('button', { name: 'Create subscription' }))

    assert.ok(capturedBody)
    const body = capturedBody as Record<string, unknown>
    assert.equal(body.customerId, 'customer-1')
    assert.equal(body.productId, 'product-1')
    assert.equal(body.defaultQuantity, 1.5)
    assert.equal(body.startDate, '2026-10-01')
    assert.equal(body.endDate, null)
    assert.ok(!('status' in body))
  })
})
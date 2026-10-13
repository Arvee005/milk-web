import assert from 'node:assert/strict'
import { after, afterEach, describe, it } from 'node:test'
import { JSDOM } from 'jsdom'
import { createElement } from 'react'
import OwnerDailyRecords from './OwnerDailyRecords.tsx'

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
const customer = { id: 'customer-1', name: 'Synthetic Customer', phone: '9400000000', status: 'ACTIVE' }
const product = { id: 'product-1', name: 'Cow milk', unit: 'LITRE', price: 60, status: 'ACTIVE' }

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

function record(overrides: Record<string, unknown> = {}) {
  return {
    id: 'record-1',
    customerId: customer.id,
    productId: product.id,
    recordDate: todayInIndia(),
    slot: 'MORNING',
    quantity: 2,
    unitPriceSnapshot: 60,
    amount: 120,
    status: 'COLLECTED',
    ...overrides,
  }
}

function recordPage(content: ReturnType<typeof record>[], number = 0, totalPages = 1) {
  return { content, number, size: 50, totalElements: content.length, totalPages }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function baseFetcher(
  records: ReturnType<typeof record>[] = [],
  customize: (url: string, init: RequestInit | undefined) => Response | Promise<Response> = () => jsonResponse(recordPage(records)),
): typeof fetch {
  return async (input, init) => {
    const url = String(input)
    if (url.endsWith('/owner/customers')) return jsonResponse([customer])
    if (url.endsWith('/owner/products')) return jsonResponse([product])
    return customize(url, init)
  }
}

async function showDailyRecords(
  fetcher: typeof fetch,
  onSessionExpired = () => undefined,
  navigation: { onShowBilling?: () => void; onShowCustomers?: () => void; onShowProducts?: () => void; onShowSubscriptions?: () => void } = {},
) {
  globalThis.fetch = fetcher
  render(createElement(OwnerDailyRecords, {
    token: 'synthetic-owner-token',
    onSignOut: () => undefined,
    onSessionExpired,
    onShowBilling: navigation.onShowBilling ?? (() => undefined),
    onShowCustomers: navigation.onShowCustomers ?? (() => undefined),
    onShowProducts: navigation.onShowProducts ?? (() => undefined),
    onShowSubscriptions: navigation.onShowSubscriptions ?? (() => undefined),
  }))
}

async function selectCreateDefaults() {
  await screen.findByRole('heading', { name: 'Record a collection' })
  const entry = within(screen.getByRole('form', { name: 'Daily record entry' }))
  fireEvent.change(entry.getByLabelText('Customer'), { target: { value: customer.id } })
  fireEvent.change(entry.getByLabelText('Product'), { target: { value: product.id } })
}

afterEach(() => {
  cleanup()
  globalThis.fetch = originalFetch
})

after(() => dom.window.close())

describe('owner daily records React workflow', { concurrency: false }, () => {
  it('navigates to every other owner section from the workspace sidebar', async () => {
    const visited: string[] = []
    await showDailyRecords(baseFetcher(), () => undefined, {
      onShowBilling: () => visited.push('billing'),
      onShowCustomers: () => visited.push('customers'),
      onShowProducts: () => visited.push('products'),
    })
    await screen.findByRole('heading', { name: 'Record a collection' })
    const nav = within(screen.getByRole('navigation', { name: 'Main navigation' }))
    fireEvent.click(nav.getByRole('button', { name: 'Billing' }))
    fireEvent.click(nav.getByRole('button', { name: 'Customers' }))
    fireEvent.click(nav.getByRole('button', { name: 'Products' }))
    assert.deepEqual(visited, ['billing', 'customers', 'products'])
    assert.equal(nav.getByRole('button', { name: 'Daily records' }).getAttribute('aria-current'), 'page')
  })

  it('shows customer/product loading state and loads the entry controls', async () => {
    let releaseCustomers!: (response: Response) => void
    const fetcher: typeof fetch = async (input) => {
      const url = String(input)
      if (url.endsWith('/owner/customers')) return new Promise<Response>((resolve) => { releaseCustomers = resolve })
      if (url.endsWith('/owner/products')) return jsonResponse([product])
      return jsonResponse(recordPage([]))
    }
    await showDailyRecords(fetcher)
    assert.ok(await screen.findByText('Loading customers and products…'))
    releaseCustomers(jsonResponse([customer]))
    assert.ok(await screen.findByRole('heading', { name: 'Record a collection' }))
    const entry = within(screen.getByRole('form', { name: 'Daily record entry' }))
    assert.ok(entry.getByLabelText('Customer').textContent?.includes(customer.name))
    assert.ok(entry.getByLabelText('Product').textContent?.includes(product.name))
  })

  it('creates a chargeable record using selected date, evening slot, quantity, and price snapshot', async () => {
    const saved: ReturnType<typeof record>[] = []
    const postBodies: Record<string, unknown>[] = []
    const fetcher = baseFetcher([], (url, init) => {
      if (url.endsWith('/owner/daily-records') && init?.method === 'POST') {
        const postBody = JSON.parse(String(init.body)) as Record<string, unknown>
        postBodies.push(postBody)
        saved.push(record({
          id: 'created-record',
          recordDate: postBody.recordDate,
          slot: postBody.slot,
          quantity: postBody.quantity,
          unitPriceSnapshot: postBody.unitPriceSnapshot,
          amount: 163.75,
          status: postBody.status,
        }))
        return jsonResponse(saved[0], 201)
      }
      return jsonResponse(recordPage(saved))
    })
    await showDailyRecords(fetcher)
    await selectCreateDefaults()
    const entry = within(screen.getByRole('form', { name: 'Daily record entry' }))
    fireEvent.change(entry.getByLabelText('Collection date'), { target: { value: todayInIndia() } })
    fireEvent.click(screen.getByRole('radio', { name: 'Evening' }))
    fireEvent.change(entry.getByLabelText('Status'), { target: { value: 'EXTRA' } })
    fireEvent.change(entry.getByLabelText(/Actual quantity/), { target: { value: '2.5' } })
    fireEvent.change(entry.getByLabelText('Unit-price snapshot'), { target: { value: '65.50' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save daily record' }))

    assert.ok(await screen.findByText('Daily record saved.'))
    assert.equal(postBodies[0].customerId, customer.id)
    assert.equal(postBodies[0].productId, product.id)
    assert.equal(postBodies[0].recordDate, todayInIndia())
    assert.equal(postBodies[0].slot, 'EVENING')
    assert.equal(postBodies[0].status, 'EXTRA')
    assert.equal(postBodies[0].quantity, 2.5)
    assert.equal(postBodies[0].unitPriceSnapshot, 65.5)
    const createdList = await screen.findByRole('list', { name: 'Recorded collections' })
    assert.ok(within(createdList).getByRole('heading', { name: customer.name }))
    assert.ok(within(createdList).getByText('Extra milk'))
    assert.ok(within(createdList).getByText('163.75'))
  })

  it('validates quantity before sending a chargeable record', async () => {
    let postCount = 0
    const fetcher = baseFetcher([], (url, init) => {
      if (url.endsWith('/owner/daily-records') && init?.method === 'POST') postCount += 1
      return jsonResponse(recordPage([]))
    })
    await showDailyRecords(fetcher)
    await selectCreateDefaults()
    const entry = within(screen.getByRole('form', { name: 'Daily record entry' }))
    fireEvent.change(entry.getByLabelText(/Actual quantity/), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save daily record' }))

    assert.match((await screen.findByRole('alert')).textContent ?? '', /quantity greater than zero/i)
    assert.equal(postCount, 0)
  })

  it('sends zero quantity and no price for skipped status and displays the saved status', async () => {
    const saved: ReturnType<typeof record>[] = []
    const postBodies: Record<string, unknown>[] = []
    const fetcher = baseFetcher([], (url, init) => {
      if (url.endsWith('/owner/daily-records') && init?.method === 'POST') {
        const postBody = JSON.parse(String(init.body)) as Record<string, unknown>
        postBodies.push(postBody)
        saved.push(record({
          status: postBody.status,
          quantity: postBody.quantity,
          unitPriceSnapshot: postBody.unitPriceSnapshot,
          amount: 0,
        }))
        return jsonResponse(saved[0], 201)
      }
      return jsonResponse(recordPage(saved))
    })
    await showDailyRecords(fetcher)
    await selectCreateDefaults()
    const entry = within(screen.getByRole('form', { name: 'Daily record entry' }))
    fireEvent.change(entry.getByLabelText('Status'), { target: { value: 'SKIPPED' } })
    assert.equal((entry.getByLabelText(/Actual quantity/) as HTMLInputElement).value, '0')
    assert.equal((entry.getByLabelText('Unit-price snapshot') as HTMLInputElement).value, '')
    fireEvent.click(screen.getByRole('button', { name: 'Save daily record' }))

    assert.ok(await screen.findByText('Daily record saved.'))
    assert.equal(postBodies[0].status, 'SKIPPED')
    assert.equal(postBodies[0].quantity, 0)
    assert.equal(postBodies[0].unitPriceSnapshot, null)
    const skippedList = await screen.findByRole('list', { name: 'Recorded collections' })
    assert.ok(within(skippedList).getByText('Skipped'))
    assert.ok(within(skippedList).getByText('0.00'))
  })

  it('shows the empty state when no records match the filters', async () => {
    await showDailyRecords(baseFetcher([]))
    assert.ok(await screen.findByRole('heading', { name: 'No records for this date' }))
    assert.equal(screen.queryByRole('list', { name: 'Recorded collections' }), null)
  })

  it('reports a rejected creation without losing the entered values', async () => {
    const fetcher = baseFetcher([], (url, init) => {
      if (url.endsWith('/owner/daily-records') && init?.method === 'POST') {
        return jsonResponse({ error: 'A daily record already exists for this customer, product, date, and slot.' }, 409)
      }
      return jsonResponse(recordPage([]))
    })
    await showDailyRecords(fetcher)
    await selectCreateDefaults()
    const entry = within(screen.getByRole('form', { name: 'Daily record entry' }))
    fireEvent.change(entry.getByLabelText(/Actual quantity/), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save daily record' }))

    assert.match((await screen.findByRole('alert')).textContent ?? '', /already exists/)
    assert.equal((entry.getByLabelText(/Actual quantity/) as HTMLInputElement).value, '2')
    assert.equal(screen.queryByText('Daily record saved.'), null)
  })

  it('reports a rejected correction and keeps the list unchanged', async () => {
    const fetcher = baseFetcher([record()], (url, init) => {
      if (url.includes('/owner/daily-records/record-1') && init?.method === 'PUT') {
        return jsonResponse({ error: 'Past daily records cannot be updated' }, 409)
      }
      return jsonResponse(recordPage([record()]))
    })
    await showDailyRecords(fetcher)
    fireEvent.click(await screen.findByRole('button', { name: 'Correct today’s record' }))
    fireEvent.change(screen.getByLabelText(/Actual quantity/), { target: { value: '2.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save correction' }))

    assert.match((await screen.findByRole('alert')).textContent ?? '', /cannot be updated/)
    assert.equal(screen.queryByText('Daily record updated.'), null)
    assert.ok(within(screen.getByRole('list', { name: 'Recorded collections' })).getByText('2 L'))
  })

  it('surfaces an API error and retries customer/product loading', async () => {
    let customerCalls = 0
    const fetcher: typeof fetch = async (input) => {
      const url = String(input)
      if (url.endsWith('/owner/customers')) {
        customerCalls += 1
        return customerCalls === 1
          ? jsonResponse({ error: 'Customer service unavailable.' }, 503)
          : jsonResponse([customer])
      }
      if (url.endsWith('/owner/products')) return jsonResponse([product])
      return jsonResponse(recordPage([]))
    }
    await showDailyRecords(fetcher)
    assert.match((await screen.findByRole('alert')).textContent ?? '', /Customer service unavailable/)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    assert.ok(await screen.findByRole('heading', { name: 'Record a collection' }))
    assert.equal(customerCalls, 2)
  })

  it('applies date, customer, product, slot, and status filters to the owner list request', async () => {
    const requestedUrls: string[] = []
    const fetcher = baseFetcher([], (url) => {
      requestedUrls.push(url)
      return jsonResponse(recordPage([]))
    })
    await showDailyRecords(fetcher)
    await screen.findByRole('heading', { name: 'Recorded collections' })
    const filterForm = within(screen.getByRole('form', { name: 'Filter daily records' }))
    fireEvent.change(filterForm.getByLabelText('Date'), { target: { value: todayInIndia() } })
    fireEvent.change(filterForm.getByLabelText('Customer'), { target: { value: customer.id } })
    fireEvent.change(filterForm.getByLabelText('Product'), { target: { value: product.id } })
    fireEvent.change(filterForm.getByLabelText('Slot'), { target: { value: 'EVENING' } })
    fireEvent.change(filterForm.getByLabelText('Status'), { target: { value: 'EXTRA' } })
    fireEvent.click(filterForm.getByRole('button', { name: 'Apply filters' }))

    await waitFor(() => assert.ok(requestedUrls.some((url) => (
      url.includes(`recordDate=${todayInIndia()}`)
        && url.includes(`customerId=${customer.id}`)
        && url.includes(`productId=${product.id}`)
        && url.includes('slot=EVENING')
        && url.includes('status=EXTRA')
    ))))
  })

  it('keeps forbidden responses visible and expires the session on unauthorized responses', async () => {
    const forbiddenFetcher: typeof fetch = async (input) => {
      const url = String(input)
      if (url.endsWith('/owner/customers')) return jsonResponse([customer])
      if (url.endsWith('/owner/products')) return jsonResponse([product])
      return jsonResponse({ error: 'Forbidden owner endpoint.' }, 403)
    }
    await showDailyRecords(forbiddenFetcher)
    assert.match((await screen.findByRole('alert')).textContent ?? '', /permission/)

    cleanup()
    let sessionExpired = false
    const unauthorizedFetcher = baseFetcher([], (url) => (
      url.includes('/owner/daily-records')
        ? jsonResponse({ error: 'Unauthorized.' }, 401)
        : jsonResponse(recordPage([]))
    ))
    await showDailyRecords(unauthorizedFetcher, () => { sessionExpired = true })
    await waitFor(() => assert.equal(sessionExpired, true))
  })

  it('allows correcting only today records and preserves a chargeable price snapshot', async () => {
    const updateBodies: Record<string, unknown>[] = []
    const updated: ReturnType<typeof record>[] = []
    const fetcher = baseFetcher([record()], (url, init) => {
      if (url.includes('/owner/daily-records/record-1') && init?.method === 'PUT') {
        const updateBody = JSON.parse(String(init.body)) as Record<string, unknown>
        updateBodies.push(updateBody)
        updated.push(record({ quantity: updateBody.quantity, amount: 150 }))
        return jsonResponse(updated[0])
      }
      return jsonResponse(recordPage(updated.length > 0 ? updated : [record()]))
    })
    await showDailyRecords(fetcher)
    await screen.findByRole('heading', { name: 'Recorded collections' })
    fireEvent.click(await screen.findByRole('button', { name: 'Correct today’s record' }))
    const entry = within(screen.getByRole('form', { name: 'Daily record entry' }))
    fireEvent.change(entry.getByLabelText(/Actual quantity/), { target: { value: '2.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save correction' }))

    assert.ok(await screen.findByText('Daily record updated.'))
    assert.equal(updateBodies[0].quantity, 2.5)
    assert.equal(updateBodies[0].status, 'COLLECTED')
    assert.equal(updateBodies[0].unitPriceSnapshot, 60)
    assert.ok(screen.getByText('150.00'))
  })
})

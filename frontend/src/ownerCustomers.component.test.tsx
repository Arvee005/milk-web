import assert from 'node:assert/strict'
import { after, afterEach, describe, it } from 'node:test'
import { JSDOM } from 'jsdom'
import { createElement } from 'react'
import OwnerCustomers from './OwnerCustomers.tsx'

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
    name: 'Synthetic Customer',
    address: '1 Test Lane',
    billingType: 'MONTHLY',
    status: 'ACTIVE',
    joinDate: '2026-08-01',
    ...overrides,
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function listFetcher(
  customers: ReturnType<typeof customer>[],
  customize: (url: string, init: RequestInit | undefined) => Response | Promise<Response> = () => jsonResponse(customers),
): typeof fetch {
  return async (input, init) => {
    const url = String(input)
    if (init?.method === 'POST' || init?.method === 'PUT') return customize(url, init)
    return jsonResponse(customers)
  }
}

async function showCustomers(fetcher: typeof fetch, onSessionExpired = () => undefined) {
  globalThis.fetch = fetcher
  render(createElement(OwnerCustomers, {
    token: 'synthetic-owner-token',
    onSignOut: () => undefined,
    onSessionExpired,
    onShowBilling: () => undefined,
    onShowDailyRecords: () => undefined,
    onShowProducts: () => undefined,
    onShowSubscriptions: () => undefined,
  }))
}

function entryForm() {
  return within(screen.getByRole('form', { name: 'Customer details' }))
}

async function fillCreateForm(overrides: { name?: string; phone?: string; password?: string } = {}) {
  const form = entryForm()
  fireEvent.change(form.getByLabelText('Name'), { target: { value: overrides.name ?? 'Ravi Kumar' } })
  fireEvent.change(form.getByLabelText('Phone'), { target: { value: overrides.phone ?? '9500000000' } })
  fireEvent.change(form.getByLabelText('Initial password'), { target: { value: overrides.password ?? 'initial-secret' } })
}

afterEach(() => {
  cleanup()
  globalThis.fetch = originalFetch
})

after(() => dom.window.close())

describe('owner customers React workflow', { concurrency: false }, () => {
  it('shows a loading state and then the customer list', async () => {
    let release = (_body: unknown) => {}
    const pending = new Promise((resolve) => {
      release = resolve
    })
    showCustomers(async () => {
      await pending
      return jsonResponse([customer()])
    })
    assert.ok(screen.getByText('Loading customers…'))
    release(null)
    assert.equal((await screen.findByRole('heading', { name: 'Synthetic Customer' })).textContent, 'Synthetic Customer')
  })

  it('shows an empty state when no customer exists yet', async () => {
    showCustomers(listFetcher([]))
    assert.equal((await screen.findByRole('heading', { name: 'No customers yet' })).textContent, 'No customers yet')
  })

  it('surfaces a load failure and retries successfully', async () => {
    let calls = 0
    showCustomers(async () => {
      calls += 1
      if (calls === 1) return jsonResponse({ error: 'Could not load customers.' }, 500)
      return jsonResponse([customer()])
    })
    const retry = await screen.findByRole('button', { name: 'Try again' })
    assert.match((screen.getByRole('alert').textContent ?? ''), /Could not load customers/)
    fireEvent.click(retry)
    assert.equal((await screen.findByRole('heading', { name: 'Synthetic Customer' })).textContent, 'Synthetic Customer')
    assert.equal(calls, 2)
  })

  it('filters the loaded list by search text and status without another request', async () => {
    const customers = [
      customer(),
      customer({ id: 'customer-2', name: 'Ravi Kumar', phone: '9500000000', status: 'PAUSED' }),
      customer({ id: 'customer-3', name: 'Sunita Devi', phone: '9600000000', status: 'INACTIVE' }),
    ]
    let calls = 0
    showCustomers(async (_input, init) => {
      if (!init?.method) calls += 1
      return jsonResponse(customers)
    })
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    assert.ok(screen.getByRole('heading', { name: 'Ravi Kumar' }))
    assert.ok(screen.getByRole('heading', { name: 'Sunita Devi' }))

    const filters = within(screen.getByRole('form', { name: 'Filter customers' }))
    fireEvent.change(filters.getByLabelText('Search'), { target: { value: 'ravi' } })
    assert.equal(screen.queryByRole('heading', { name: 'Synthetic Customer' }), null)
    assert.ok(screen.getByRole('heading', { name: 'Ravi Kumar' }))

    fireEvent.change(filters.getByLabelText('Search'), { target: { value: '' } })
    fireEvent.change(filters.getByLabelText('Status'), { target: { value: 'INACTIVE' } })
    assert.ok(screen.getByRole('heading', { name: 'Sunita Devi' }))
    assert.equal(screen.queryByRole('heading', { name: 'Ravi Kumar' }), null)

    fireEvent.change(filters.getByLabelText('Status'), { target: { value: '' } })
    fireEvent.change(filters.getByLabelText('Search'), { target: { value: '96000' } })
    assert.ok(screen.getByRole('heading', { name: 'Sunita Devi' }))
    assert.equal(calls, 1)
  })

  it('reports when no customer matches the current filters', async () => {
    showCustomers(listFetcher([customer()]))
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    const filters = within(screen.getByRole('form', { name: 'Filter customers' }))
    fireEvent.change(filters.getByLabelText('Search'), { target: { value: 'nobody' } })
    assert.ok(await screen.findByRole('heading', { name: 'No customers match' }))
  })

  it('creates a customer with the exact payload and refreshes the list', async () => {
    let sentBody = ''
    let method = ''
    const created = customer({ id: 'customer-new', name: 'Ravi Kumar', phone: '9500000000' })
    showCustomers(listFetcher([customer()], (_url, init) => {
      method = init?.method ?? ''
      sentBody = String(init?.body)
      return jsonResponse(created, 201)
    }))
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    await fillCreateForm()
    fireEvent.change(entryForm().getByLabelText('Join date'), { target: { value: '2026-09-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create customer' }))
    await screen.findByText('Customer Ravi Kumar created. Share the initial password securely; it cannot be reset from this screen.')
    assert.equal(method, 'POST')
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Ravi Kumar',
      phone: '9500000000',
      password: 'initial-secret',
      address: null,
      billingType: 'MONTHLY',
      status: 'ACTIVE',
      joinDate: '2026-09-01',
    })
  })

  it('blocks submission with client validation before sending a request', async () => {
    let called = false
    showCustomers(listFetcher([customer()], () => {
      called = true
      return jsonResponse(customer(), 201)
    }))
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    const form = entryForm()
    fireEvent.change(form.getByLabelText('Name'), { target: { value: 'Ravi Kumar' } })
    fireEvent.change(form.getByLabelText('Phone'), { target: { value: '95000' } })
    fireEvent.change(form.getByLabelText('Initial password'), { target: { value: 'short' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create customer' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /10-digit/)
    assert.equal(called, false)

    fireEvent.change(entryForm().getByLabelText('Phone'), { target: { value: '9500000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create customer' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /at least 8 characters/)
    assert.equal(called, false)
  })

  it('keeps entered values and shows the conflict when the phone is already registered', async () => {
    showCustomers(listFetcher([customer()], () => jsonResponse({ error: 'Phone number already registered: 9500000000' }, 409)))
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    await fillCreateForm()
    fireEvent.click(screen.getByRole('button', { name: 'Create customer' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /already registered/)
    assert.equal((entryForm().getByLabelText('Name') as HTMLInputElement).value, 'Ravi Kumar')
    assert.equal((entryForm().getByLabelText('Phone') as HTMLInputElement).value, '9500000000')
  })

  it('edits a customer with every field the full-replacement contract requires and keeps the phone immutable', async () => {
    let sentBody = ''
    let requestUrl = ''
    showCustomers(listFetcher([customer()], (url, init) => {
      requestUrl = url
      sentBody = String(init?.body)
      return jsonResponse(customer({ name: 'Renamed Customer', billingType: 'PREPAID', status: 'PAUSED' }))
    }))
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit details' }))
    await screen.findByRole('heading', { name: 'Edit Synthetic Customer' })
    const form = entryForm()
    assert.equal((form.getByLabelText('Phone') as HTMLInputElement).readOnly, true)
    assert.equal(form.queryByLabelText('Initial password'), null)

    fireEvent.change(form.getByLabelText('Name'), { target: { value: 'Renamed Customer' } })
    fireEvent.change(form.getByLabelText('Billing type'), { target: { value: 'PREPAID' } })
    fireEvent.change(form.getByLabelText('Status'), { target: { value: 'PAUSED' } })
    fireEvent.change(form.getByLabelText('Join date'), { target: { value: '2026-09-15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save customer' }))
    await screen.findByText('Customer Renamed Customer updated.')
    assert.match(requestUrl, /\/owner\/customers\/customer-1$/)
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Renamed Customer',
      address: '1 Test Lane',
      billingType: 'PREPAID',
      status: 'PAUSED',
      joinDate: '2026-09-15',
    })
  })

  it('changes customer status from the list using a full-replacement update', async () => {
    let sentBody = ''
    showCustomers(listFetcher([customer()], (_url, init) => {
      sentBody = String(init?.body)
      return jsonResponse(customer({ status: 'INACTIVE' }))
    }))
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await screen.findByText('Customer Synthetic Customer is now inactive.')
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Synthetic Customer',
      address: '1 Test Lane',
      billingType: 'MONTHLY',
      status: 'INACTIVE',
      joinDate: '2026-08-01',
    })
  })

  it('keeps a forbidden response visible without expiring the session', async () => {
    let sessionExpired = false
    showCustomers(listFetcher([customer()], () => jsonResponse({ error: 'Forbidden.' }, 403)), () => {
      sessionExpired = true
    })
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => {
      assert.match(screen.getByRole('alert').textContent ?? '', /permission/)
    })
    assert.equal(sessionExpired, false)
  })

  it('expires the session on an unauthorized response', async () => {
    let sessionExpired = false
    showCustomers(listFetcher([customer()], () => jsonResponse({ error: 'Unauthorized' }, 401)), () => {
      sessionExpired = true
    })
    await screen.findByRole('heading', { name: 'Synthetic Customer' })
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => {
      assert.equal(sessionExpired, true)
    })
  })
})

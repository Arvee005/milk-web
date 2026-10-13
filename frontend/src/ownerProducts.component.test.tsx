import assert from 'node:assert/strict'
import { after, afterEach, describe, it } from 'node:test'
import { JSDOM } from 'jsdom'
import { createElement } from 'react'
import OwnerProducts from './OwnerProducts.tsx'

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

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function listFetcher(
  products: ReturnType<typeof product>[],
  customize: (url: string, init: RequestInit | undefined) => Response | Promise<Response> = () => jsonResponse(products),
): typeof fetch {
  return async (input, init) => {
    const url = String(input)
    if (init?.method === 'POST' || init?.method === 'PUT') return customize(url, init)
    return jsonResponse(products)
  }
}

async function showProducts(fetcher: typeof fetch, onSessionExpired = () => undefined) {
  globalThis.fetch = fetcher
  render(createElement(OwnerProducts, {
    token: 'synthetic-owner-token',
    onSignOut: () => undefined,
    onSessionExpired,
    onShowBilling: () => undefined,
    onShowDailyRecords: () => undefined,
    onShowCustomers: () => undefined,
    onShowSubscriptions: () => undefined,
  }))
}

function entryForm() {
  return within(screen.getByRole('form', { name: 'Product details' }))
}

afterEach(() => {
  cleanup()
  globalThis.fetch = originalFetch
})

after(() => dom.window.close())

describe('owner products React workflow', { concurrency: false }, () => {
  it('shows a loading state and then the product list', async () => {
    let release = (_body: unknown) => {}
    const pending = new Promise((resolve) => {
      release = resolve
    })
    showProducts(async () => {
      await pending
      return jsonResponse([product()])
    })
    assert.ok(screen.getByText('Loading products…'))
    release(null)
    assert.equal((await screen.findByRole('heading', { name: 'Cow milk' })).textContent, 'Cow milk')
  })

  it('shows an empty state when no product exists yet', async () => {
    showProducts(listFetcher([]))
    assert.equal((await screen.findByRole('heading', { name: 'No products yet' })).textContent, 'No products yet')
  })

  it('surfaces a load failure and retries successfully', async () => {
    let calls = 0
    showProducts(async () => {
      calls += 1
      if (calls === 1) return jsonResponse({ error: 'Could not load products.' }, 500)
      return jsonResponse([product()])
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Try again' }))
    assert.equal((await screen.findByRole('heading', { name: 'Cow milk' })).textContent, 'Cow milk')
    assert.equal(calls, 2)
  })

  it('creates a product without sending a status field', async () => {
    let sentBody = ''
    let method = ''
    showProducts(listFetcher([product()], (_url, init) => {
      method = init?.method ?? ''
      sentBody = String(init?.body)
      return jsonResponse(product({ name: 'Buffalo milk', price: 80 }), 201)
    }))
    await screen.findByRole('heading', { name: 'Cow milk' })
    const form = entryForm()
    fireEvent.change(form.getByLabelText('Name'), { target: { value: 'Buffalo milk' } })
    fireEvent.change(form.getByLabelText('Price per unit'), { target: { value: '80' } })
    fireEvent.change(form.getByLabelText('Description'), { target: { value: 'Buffalo milk' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }))
    await screen.findByText('Product Buffalo milk created as active.')
    assert.equal(method, 'POST')
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Buffalo milk',
      description: 'Buffalo milk',
      unit: 'LITRE',
      price: 80,
    })
    assert.equal('status' in JSON.parse(sentBody), false)
  })

  it('blocks submission with client validation before sending a request', async () => {
    let called = false
    showProducts(listFetcher([product()], () => {
      called = true
      return jsonResponse(product(), 201)
    }))
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /Enter the product name/)
    assert.equal(called, false)

    fireEvent.change(entryForm().getByLabelText('Name'), { target: { value: 'Buffalo milk' } })
    fireEvent.change(entryForm().getByLabelText('Price per unit'), { target: { value: '-5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /price with up to 10 whole digits/)
    assert.equal(called, false)

    fireEvent.change(entryForm().getByLabelText('Price per unit'), { target: { value: '12.345' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /2 decimal places/)
    assert.equal(called, false)
  })

  it('shows the duplicate-name conflict and keeps the entered values', async () => {
    showProducts(listFetcher([product()], () => jsonResponse({ error: 'Product name already exists: Buffalo milk' }, 409)))
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.change(entryForm().getByLabelText('Name'), { target: { value: 'Buffalo milk' } })
    fireEvent.change(entryForm().getByLabelText('Price per unit'), { target: { value: '80' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create product' }))
    assert.match((await screen.findByRole('alert')).textContent ?? '', /already exists/)
    assert.equal((entryForm().getByLabelText('Name') as HTMLInputElement).value, 'Buffalo milk')
    assert.equal((entryForm().getByLabelText('Price per unit') as HTMLInputElement).value, '80')
  })

  it('edits price and details without asking for identity confirmation', async () => {
    let sentBody = ''
    let requestUrl = ''
    showProducts(listFetcher([product()], (url, init) => {
      requestUrl = url
      sentBody = String(init?.body)
      return jsonResponse(product({ price: 82.5, description: null }))
    }))
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit product' }))
    await screen.findByRole('heading', { name: 'Edit Cow milk' })
    const form = entryForm()
    fireEvent.change(form.getByLabelText('Price per unit'), { target: { value: '82.5' } })
    fireEvent.change(form.getByLabelText('Description'), { target: { value: '' } })
    assert.equal(screen.queryByText('This changes how historical records are labelled'), null)
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }))
    await screen.findByText('Product Cow milk updated.')
    assert.match(requestUrl, /\/owner\/products\/product-1$/)
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Cow milk',
      description: null,
      unit: 'LITRE',
      price: 82.5,
      status: 'ACTIVE',
    })
  })

  it('requires confirmation before saving a name or unit change and explains the effect', async () => {
    let sentBody = ''
    showProducts(listFetcher([product()], (_url, init) => {
      sentBody = String(init?.body)
      return jsonResponse(product({ name: 'Buffalo milk' }))
    }))
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit product' }))
    await screen.findByRole('heading', { name: 'Edit Cow milk' })
    fireEvent.change(entryForm().getByLabelText('Name'), { target: { value: 'Buffalo milk' } })

    fireEvent.click(screen.getByRole('button', { name: 'Save product' }))
    const warning = await screen.findByRole('alert')
    assert.match(warning.textContent ?? '', /This changes how historical records are labelled/)
    assert.match(
      warning.textContent ?? '',
      /Recorded quantities, price snapshots, amounts, and issued bills are not affected/,
    )
    assert.equal(sentBody, '')

    fireEvent.click(screen.getByRole('button', { name: 'Confirm and save' }))
    await screen.findByText('Product Buffalo milk updated.')
    assert.equal(JSON.parse(sentBody).name, 'Buffalo milk')
  })

  it('requires confirmation when only the unit changes', async () => {
    let sentBody = ''
    showProducts(listFetcher([product()], (_url, init) => {
      sentBody = String(init?.body)
      return jsonResponse(product({ unit: 'KILOGRAM' }))
    }))
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit product' }))
    await screen.findByRole('heading', { name: 'Edit Cow milk' })
    fireEvent.change(entryForm().getByLabelText('Unit'), { target: { value: 'KILOGRAM' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save product' }))
    await screen.findByText('This changes how historical records are labelled')
    assert.equal(sentBody, '')
    fireEvent.click(screen.getByRole('button', { name: 'Confirm and save' }))
    await screen.findByText('Product Cow milk updated.')
    assert.equal(JSON.parse(sentBody).unit, 'KILOGRAM')
  })

  it('drops the warning when the owner restores the original name', async () => {
    showProducts(listFetcher([product()], () => jsonResponse(product())))
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit product' }))
    await screen.findByRole('heading', { name: 'Edit Cow milk' })
    fireEvent.change(entryForm().getByLabelText('Name'), { target: { value: 'Buffalo milk' } })
    assert.ok(screen.getByText('This changes how historical records are labelled'))
    fireEvent.change(entryForm().getByLabelText('Name'), { target: { value: 'Cow milk' } })
    assert.equal(screen.queryByText('This changes how historical records are labelled'), null)
  })

  it('deactivates and reactivates a product with a full-replacement update', async () => {
    const sent: string[] = []
    let products = [product()]
    showProducts(async (input, init) => {
      if (init?.method === 'PUT') {
        sent.push(String(init?.body))
        products = [product({ status: JSON.parse(String(init?.body)).status })]
        return jsonResponse(products[0])
      }
      assert.match(String(input), /\/owner\/products$/)
      return jsonResponse(products)
    })
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await screen.findByText('Product Cow milk is now inactive.')
    assert.deepEqual(JSON.parse(sent[0]), {
      name: 'Cow milk',
      description: 'Whole milk',
      unit: 'LITRE',
      price: 60,
      status: 'INACTIVE',
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Activate' }))
    await screen.findByText('Product Cow milk is now active.')
    assert.equal(JSON.parse(sent[1]).status, 'ACTIVE')
  })

  it('keeps a forbidden response visible without expiring the session', async () => {
    let sessionExpired = false
    showProducts(listFetcher([product()], () => jsonResponse({ error: 'Forbidden.' }, 403)), () => {
      sessionExpired = true
    })
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => {
      assert.match(screen.getByRole('alert').textContent ?? '', /permission/)
    })
    assert.equal(sessionExpired, false)
  })

  it('expires the session on an unauthorized response', async () => {
    let sessionExpired = false
    showProducts(listFetcher([product()], () => jsonResponse({ error: 'Unauthorized' }, 401)), () => {
      sessionExpired = true
    })
    await screen.findByRole('heading', { name: 'Cow milk' })
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => {
      assert.equal(sessionExpired, true)
    })
  })
})

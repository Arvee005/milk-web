import assert from 'node:assert/strict'
import { after, afterEach, describe, it } from 'node:test'
import { JSDOM } from 'jsdom'
import { createElement } from 'react'
import CustomerWorkspace from './CustomerWorkspace.tsx'

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

const { cleanup, fireEvent, render, screen, waitFor } = await import('@testing-library/react')
const originalFetch = globalThis.fetch

const profile = {
  id: 'customer-id',
  userId: 'user-id',
  phone: '9400000000',
  name: 'Synthetic Customer',
  address: 'Test address',
  billingType: 'MONTHLY',
  status: 'ACTIVE',
  joinDate: '2026-01-01',
}

const septemberBill = {
  id: 'september-bill',
  period: '2026-09',
  periodStart: '2026-09-01',
  periodEnd: '2026-09-30',
  totalAmount: 135,
  status: 'ISSUED',
  issuedAt: '2026-10-01T10:30:00Z',
  lines: [{
    recordDate: '2026-09-03',
    slot: 'MORNING',
    description: 'Cow milk (LITRE)',
    quantity: 1.5,
    unitPrice: 60,
    amount: 90,
  }],
}

const octoberBill = {
  ...septemberBill,
  id: 'october-bill',
  period: '2026-10',
  periodStart: '2026-10-01',
  periodEnd: '2026-10-31',
  totalAmount: 210,
  issuedAt: '2026-11-01T10:30:00Z',
}

function page(content: typeof septemberBill[], number = 0, totalPages = 1) {
  return { content, number, size: 10, totalElements: totalPages * content.length, totalPages }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function showBills(fetcher: typeof fetch) {
  globalThis.fetch = fetcher
  render(
    createElement(CustomerWorkspace, {
      token: 'synthetic-token',
      onSignOut: () => undefined,
      onSessionExpired: () => undefined,
    }),
  )
  fireEvent.click(screen.getByRole('button', { name: 'Bills' }))
}

function fetchWithBills(
  bills: ReturnType<typeof page> | ((requestUrl: string) => ReturnType<typeof page>),
): typeof fetch {
  return async (input) => {
    const requestUrl = String(input)
    if (requestUrl.endsWith('/customer/me')) return jsonResponse(profile)
    const result = typeof bills === 'function' ? bills(requestUrl) : bills
    return jsonResponse(result)
  }
}

afterEach(() => {
  cleanup()
  globalThis.fetch = originalFetch
})

after(() => dom.window.close())

describe('customer bills React view', { concurrency: false }, () => {
  it('renders issued bills with their billing period and total without payment claims', async () => {
    await showBills(fetchWithBills(page([septemberBill])))

    assert.ok(await screen.findByRole('heading', { name: 'September 2026' }))
    assert.ok(screen.getByText('135.00'))
    assert.ok(screen.getAllByText('Issued').length >= 1)
    assert.equal(screen.queryByText(/paid|unpaid/i), null)
  })

  it('expands and collapses the bill line summary', async () => {
    await showBills(fetchWithBills(page([septemberBill])))

    const showDetails = await screen.findByRole('button', { name: 'View bill details (1 items)' })
    fireEvent.click(showDetails)
    assert.ok(screen.getByRole('heading', { name: 'Bill items' }))
    assert.ok(screen.getByText('Cow milk (LITRE)'))

    fireEvent.click(screen.getByRole('button', { name: 'Hide bill details' }))
    assert.equal(screen.queryByText('Cow milk (LITRE)'), null)
  })

  it('shows the empty state when the customer has no issued bills', async () => {
    await showBills(fetchWithBills(page([])))

    assert.ok(await screen.findByRole('heading', { name: 'No issued bills yet' }))
  })

  it('shows a loading state while the bills request is pending', async () => {
    let resolveBills!: (response: Response) => void
    const fetcher: typeof fetch = async (input) => {
      if (String(input).endsWith('/customer/me')) return jsonResponse(profile)
      return new Promise<Response>((resolve) => {
        resolveBills = resolve
      })
    }
    await showBills(fetcher)

    assert.ok(await screen.findByText('Loading your issued bills…'))
    resolveBills(jsonResponse(page([septemberBill])))
    assert.ok(await screen.findByRole('heading', { name: 'September 2026' }))
  })

  it('shows an API error and retries successfully', async () => {
    let billAttempts = 0
    let failBillRequests = true
    const fetcher: typeof fetch = async (input) => {
      if (String(input).endsWith('/customer/me')) return jsonResponse(profile)
      billAttempts += 1
      return failBillRequests
        ? jsonResponse({ error: 'Temporary billing service failure.' }, 503)
        : jsonResponse(page([septemberBill]))
    }
    await showBills(fetcher)

    assert.ok(await screen.findByRole('alert'))
    assert.match(screen.getByRole('alert').textContent ?? '', /Temporary billing service failure/)
    const attemptsBeforeRetry = billAttempts
    failBillRequests = false
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    assert.ok(await screen.findByRole('heading', { name: 'September 2026' }))
    assert.ok(billAttempts > attemptsBeforeRetry)
  })

  it('requests the selected page when navigating forward and backward', async () => {
    const requestedPages: string[] = []
    const fetcher: typeof fetch = async (input) => {
      const requestUrl = String(input)
      if (requestUrl.endsWith('/customer/me')) return jsonResponse(profile)
      const requestedPage = new URL(requestUrl, 'http://localhost').searchParams.get('page') ?? '0'
      requestedPages.push(requestedPage)
      return requestedPage === '0'
        ? jsonResponse(page([septemberBill], 0, 2))
        : jsonResponse(page([octoberBill], 1, 2))
    }
    await showBills(fetcher)
    assert.ok(await screen.findByRole('heading', { name: 'September 2026' }))
    assert.ok(screen.getByText('Page 1 of 2'))

    const requestsBeforeNext = requestedPages.length
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => assert.ok(requestedPages.slice(requestsBeforeNext).includes('1')))
    assert.ok(await screen.findByRole('heading', { name: 'October 2026' }))
    assert.ok(screen.getByText('Page 2 of 2'))

    const requestsBeforePrevious = requestedPages.length
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
    await waitFor(() => assert.ok(requestedPages.slice(requestsBeforePrevious).includes('0')))
    await waitFor(() => assert.ok(screen.getByRole('heading', { name: 'September 2026' })))
  })
})

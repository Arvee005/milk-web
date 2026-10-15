import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ApiError } from './authApi.ts'
import {
  createOwnerProduct,
  formatProductPrice,
  getOwnerProducts,
  ownerProductErrorMessage,
  productStatusLabel,
  productUnitLabel,
  updateOwnerProduct,
  type OwnerProduct,
  type UpdateOwnerProduct,
} from './ownerProductsApi.ts'

const product = (overrides: Partial<OwnerProduct> = {}): OwnerProduct => ({
  id: 'product-id',
  name: 'Cow milk',
  description: 'Whole milk',
  unit: 'LITRE',
  price: 60,
  status: 'ACTIVE',
  createdAt: '2026-08-01T04:00:00Z',
  updatedAt: '2026-08-01T04:00:00Z',
  ...overrides,
})

const replacement = (overrides: Partial<UpdateOwnerProduct> = {}): UpdateOwnerProduct => ({
  name: 'Cow milk',
  description: 'Whole milk',
  unit: 'LITRE',
  price: 60,
  status: 'ACTIVE',
  ...overrides,
})

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('owner products API client', () => {
  it('requests the product list and returns every management field', async () => {
    let requestUrl = ''
    const results = await getOwnerProducts('owner-token', {
      apiBase: '/api',
      fetcher: async (input) => {
        requestUrl = String(input)
        return jsonResponse([product(), product({ id: 'second', name: 'Cow ghee', unit: 'KILOGRAM', status: 'INACTIVE' })])
      },
    })
    assert.equal(requestUrl, '/api/owner/products')
    assert.equal(results.length, 2)
    assert.equal(results[0].description, 'Whole milk')
    assert.equal(results[0].updatedAt, '2026-08-01T04:00:00Z')
    assert.equal(results[1].unit, 'KILOGRAM')
    assert.equal(results[1].status, 'INACTIVE')
  })

  it('creates a product without a status field because the server provisions it as active', async () => {
    let sentBody = ''
    let method = ''
    const created = await createOwnerProduct('owner-token', {
      name: 'Buffalo milk',
      description: null,
      unit: 'LITRE',
      price: 80,
    }, {
      apiBase: '/api',
      fetcher: async (input, init) => {
        method = init?.method ?? ''
        sentBody = String(init?.body)
        assert.equal(String(input), '/api/owner/products')
        return jsonResponse(product({ name: 'Buffalo milk', description: null, price: 80 }), 201)
      },
    })
    assert.equal(method, 'POST')
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Buffalo milk',
      description: null,
      unit: 'LITRE',
      price: 80,
    })
    assert.equal('status' in JSON.parse(sentBody), false)
    assert.equal(created.status, 'ACTIVE')
  })

  it('updates a product with the existing status included in the full-replacement body', async () => {
    let sentBody = ''
    let method = ''
    let requestUrl = ''
    await updateOwnerProduct('owner-token', 'product-id', replacement({
      name: 'Buffalo milk',
      description: null,
      price: 82.5,
      status: 'INACTIVE',
    }), {
      apiBase: '/api',
      fetcher: async (input, init) => {
        method = init?.method ?? ''
        requestUrl = String(input)
        sentBody = String(init?.body)
        return jsonResponse(product({ name: 'Buffalo milk', description: null, price: 82.5, status: 'INACTIVE' }))
      },
    })
    assert.equal(method, 'PUT')
    assert.equal(requestUrl, '/api/owner/products/product-id')
    assert.deepEqual(JSON.parse(sentBody), {
      name: 'Buffalo milk',
      description: null,
      unit: 'LITRE',
      price: 82.5,
      status: 'INACTIVE',
    })
  })

  it('preserves 401, 403, and 409 statuses and rejects malformed products', async () => {
    for (const status of [401, 403, 409]) {
      await assert.rejects(
        createOwnerProduct('owner-token', {
          name: 'Cow milk',
          description: null,
          unit: 'LITRE',
          price: 60,
        }, {
          fetcher: async () => jsonResponse({ error: 'Product name already exists: Cow milk' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
      await assert.rejects(
        updateOwnerProduct('owner-token', 'product-id', replacement(), {
          fetcher: async () => jsonResponse({ error: 'Conflict.' }, status),
        }),
        (error: unknown) => error instanceof ApiError && error.status === status,
      )
    }
    await assert.rejects(
      getOwnerProducts('owner-token', {
        fetcher: async () => jsonResponse([{ ...product(), unit: 'POUND' }]),
      }),
      /invalid product data/,
    )
    await assert.rejects(
      getOwnerProducts('owner-token', {
        fetcher: async () => jsonResponse([{ ...product(), price: 'sixty' }]),
      }),
      /invalid product data/,
    )
    await assert.rejects(
      updateOwnerProduct('owner-token', 'product-id', replacement(), {
        fetcher: async () => jsonResponse({ ...product(), status: 'ARCHIVED' }),
      }),
      /invalid product/,
    )
    assert.match(ownerProductErrorMessage(new TypeError('Failed to fetch')), /connection/)
  })

  it('rejects empty and malformed product timestamps before they can reach the date formatter', async () => {
    for (const malformed of ['', 'bad', '2026-13-45']) {
      await assert.rejects(
        getOwnerProducts('owner-token', {
          fetcher: async () => jsonResponse([{ ...product(), updatedAt: malformed }]),
        }),
        /invalid product data/,
      )
      await assert.rejects(
        getOwnerProducts('owner-token', {
          fetcher: async () => jsonResponse([{ ...product(), createdAt: malformed }]),
        }),
        /invalid product data/,
      )
    }
    await assert.rejects(
      createOwnerProduct('owner-token', { name: 'Cow milk', description: null, unit: 'LITRE', price: 60 }, {
        fetcher: async () => jsonResponse({ ...product(), updatedAt: '2026-02-30T00:00:00Z' }, 201),
      }),
      /invalid product/,
    )
    await assert.rejects(
      updateOwnerProduct('owner-token', 'product-id', replacement(), {
        fetcher: async () => jsonResponse({ ...product(), updatedAt: '' }),
      }),
      /invalid product/,
    )
    const accepted = await getOwnerProducts('owner-token', {
      fetcher: async () => jsonResponse([product({ updatedAt: '2026-12-31T18:30:00Z' })]),
    })
    assert.equal(accepted[0].updatedAt, '2026-12-31T18:30:00Z')
  })

  it('formats prices and renders human labels for unit and status', () => {
    assert.equal(formatProductPrice(82.5), '₹82.50')
    assert.equal(formatProductPrice(1400), '₹1,400.00')
    assert.equal(productUnitLabel('LITRE'), 'Litre')
    assert.equal(productUnitLabel('KILOGRAM'), 'Kilogram')
    assert.equal(productStatusLabel('ACTIVE'), 'Active')
    assert.equal(productStatusLabel('INACTIVE'), 'Inactive')
  })
})

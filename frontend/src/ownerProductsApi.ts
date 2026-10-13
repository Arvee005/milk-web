import { apiRequest } from './authApi.ts'

export type OwnerProductUnit = 'LITRE' | 'KILOGRAM'
export type OwnerProductStatus = 'ACTIVE' | 'INACTIVE'

export type OwnerProduct = {
  id: string
  name: string
  description: string | null
  unit: OwnerProductUnit
  price: number
  status: OwnerProductStatus
  createdAt: string
  updatedAt: string
}

// The create endpoint does not accept a status: the backend always provisions a
// new product as ACTIVE.
export type CreateOwnerProduct = {
  name: string
  description: string | null
  unit: OwnerProductUnit
  price: number
}

// The update endpoint is a full replacement and requires the existing status so
// activation and deactivation travel with the same contract.
export type UpdateOwnerProduct = {
  name: string
  description: string | null
  unit: OwnerProductUnit
  price: number
  status: OwnerProductStatus
}

const PRODUCT_UNIT_LABELS: Record<OwnerProductUnit, string> = {
  LITRE: 'Litre',
  KILOGRAM: 'Kilogram',
}

const PRODUCT_STATUS_LABELS: Record<OwnerProductStatus, string> = {
  ACTIVE: 'Active',
  INACTIVE: 'Inactive',
}

export function productUnitLabel(unit: OwnerProductUnit) {
  return PRODUCT_UNIT_LABELS[unit]
}

export function productStatusLabel(status: OwnerProductStatus) {
  return PRODUCT_STATUS_LABELS[status]
}

function validDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

// The product list renders these timestamps as calendar dates, so the leading
// YYYY-MM-DD segment must be a real date. An empty or malformed instant would
// otherwise reach the formatter and throw instead of failing this guard.
function validInstant(value: string) {
  return validDate(value.slice(0, 10))
}

function isOwnerProduct(value: unknown): value is OwnerProduct {
  return typeof value === 'object' && value !== null
    && 'id' in value && typeof value.id === 'string'
    && 'name' in value && typeof value.name === 'string'
    && 'description' in value && (typeof value.description === 'string' || value.description === null)
    && 'unit' in value && (value.unit === 'LITRE' || value.unit === 'KILOGRAM')
    && 'price' in value && typeof value.price === 'number'
    && 'status' in value && (value.status === 'ACTIVE' || value.status === 'INACTIVE')
    && 'createdAt' in value && typeof value.createdAt === 'string' && validInstant(value.createdAt)
    && 'updatedAt' in value && typeof value.updatedAt === 'string' && validInstant(value.updatedAt)
}

export async function getOwnerProducts(
  token: string,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>('/owner/products', token, undefined, {
    ...options,
    fallback: 'Could not load products.',
  })
  if (!Array.isArray(body) || !body.every(isOwnerProduct)) {
    throw new Error('The server returned invalid product data.')
  }
  return body
}

export async function createOwnerProduct(
  token: string,
  product: CreateOwnerProduct,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>('/owner/products', token, {
    method: 'POST',
    body: JSON.stringify(product),
  }, {
    ...options,
    fallback: 'Could not create the product.',
  })
  if (!isOwnerProduct(body)) throw new Error('The server returned an invalid product.')
  return body
}

export async function updateOwnerProduct(
  token: string,
  productId: string,
  product: UpdateOwnerProduct,
  options: Parameters<typeof apiRequest>[3] = {},
) {
  const body = await apiRequest<unknown>(`/owner/products/${productId}`, token, {
    method: 'PUT',
    body: JSON.stringify(product),
  }, {
    ...options,
    fallback: 'Could not update the product.',
  })
  if (!isOwnerProduct(body)) throw new Error('The server returned an invalid product.')
  return body
}

export function formatProductPrice(value: number) {
  return new Intl.NumberFormat('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

export function ownerProductErrorMessage(error: unknown) {
  if (error instanceof TypeError) {
    return 'Unable to connect to Milk Web. Check your connection and try again.'
  }
  if (error instanceof Error) return error.message
  return 'Could not load products.'
}

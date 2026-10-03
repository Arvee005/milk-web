import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import {
  ApiError,
  TOKEN_KEY,
  apiRequest,
  clearSessionToken,
  getCurrentUser,
  isUnauthenticatedError,
  isOwner,
  login,
  readSessionToken,
  storeSessionToken,
  workspaceForRole,
} from './authApi.ts'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('authentication API client', () => {
  it('allows only OWNER identities into the owner workspace', () => {
    assert.equal(isOwner({ role: 'OWNER' }), true)
    assert.equal(isOwner({ role: 'CUSTOMER' }), false)
  })

  it('routes only the role returned by current-user validation to its workspace', async () => {
    const verifiedUsers = [
      { userId: 'owner-id', phone: '9876543210', role: 'OWNER' },
      { userId: 'customer-id', phone: '9876543211', role: 'CUSTOMER' },
    ] as const
    const fetcher: typeof fetch = async (_input, init) => {
      const token = new Headers(init?.headers).get('Authorization')
      const user = verifiedUsers.find((candidate) => candidate.userId === (token === 'Bearer owner-token'
        ? 'owner-id'
        : 'customer-id'))
      return jsonResponse(user)
    }

    const owner = await getCurrentUser('owner-token', { apiBase: '/api', fetcher })
    const customer = await getCurrentUser('customer-token', { apiBase: '/api', fetcher })
    assert.equal(workspaceForRole(owner.role), 'owner')
    assert.equal(workspaceForRole(customer.role), 'customer')
  })

  it('does not use a stale login-response role to select the workspace', async () => {
    const fetcher: typeof fetch = async (input, init) => {
      if (String(input).endsWith('/auth/login')) {
        return jsonResponse({
          token: 'verified-customer-token',
          userId: 'stale-owner-id',
          phone: '9876543210',
          role: 'OWNER',
        })
      }
      assert.equal(new Headers(init?.headers).get('Authorization'), 'Bearer verified-customer-token')
      return jsonResponse({
        userId: 'current-customer-id',
        phone: '9876543210',
        role: 'CUSTOMER',
      })
    }

    const loginResponse = await login('9876543210', 'example-password', {
      apiBase: '/api',
      fetcher,
    })
    const currentUser = await getCurrentUser(loginResponse.token, { apiBase: '/api', fetcher })

    assert.equal(workspaceForRole(loginResponse.role), 'owner')
    assert.equal(workspaceForRole(currentUser.role), 'customer')
  })

  it('stores and clears the session token under the tab-scoped key', () => {
    const values = new Map<string, string>()
    const storage: Storage = {
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
      removeItem: (key) => values.delete(key),
      clear: () => values.clear(),
      key: (index) => [...values.keys()][index] ?? null,
      get length() { return values.size },
    }

    storeSessionToken('test-token', storage)
    assert.equal(TOKEN_KEY, 'milk-web-owner-token')
    assert.equal(readSessionToken(storage), 'test-token')
    clearSessionToken(storage)
    assert.equal(readSessionToken(storage), '')
  })

  it('sends phone and password to the backend login endpoint and parses the role', async () => {
    let receivedUrl = ''
    let receivedInit: RequestInit | undefined
    const fetcher: typeof fetch = async (input, init) => {
      receivedUrl = String(input)
      receivedInit = init
      return jsonResponse({
        token: 'test-token',
        userId: 'user-id',
        phone: '9876543210',
        role: 'OWNER',
      })
    }

    const result = await login('9876543210', 'example-password', {
      apiBase: '/api',
      fetcher,
    })

    assert.equal(receivedUrl, '/api/auth/login')
    assert.equal(receivedInit?.method, 'POST')
    assert.equal(new Headers(receivedInit?.headers).get('Content-Type'), 'application/json')
    assert.deepEqual(JSON.parse(String(receivedInit?.body)), {
      phone: '9876543210',
      password: 'example-password',
    })
    assert.equal(result.role, 'OWNER')
  })

  it('rejects invalid phone input before sending a request', async () => {
    let requestMade = false
    const fetcher: typeof fetch = async () => {
      requestMade = true
      return jsonResponse({})
    }

    await assert.rejects(
      login('123', 'password', { fetcher }),
      (error: unknown) => error instanceof ApiError
        && error.status === 400
        && error.message.includes('10-digit'),
    )
    assert.equal(requestMade, false)
  })

  it('returns a clear message for invalid credentials', async () => {
    const fetcher: typeof fetch = async () => jsonResponse(
      { error: 'Invalid phone or password' },
      401,
    )

    await assert.rejects(
      login('9876543210', 'wrong-password', { fetcher }),
      (error: unknown) => error instanceof ApiError
        && error.status === 401
        && error.message === 'Phone number or password is incorrect.',
    )
  })

  it('accepts a valid CUSTOMER login response without treating it as workspace authorization', async () => {
    const fetcher: typeof fetch = async () => jsonResponse({
      token: 'customer-token',
      userId: 'customer-id',
      phone: '9876543211',
      role: 'CUSTOMER',
    })

    const result = await login('9876543211', 'example-password', { fetcher })
    assert.equal(result.role, 'CUSTOMER')
    assert.equal(workspaceForRole(result.role), 'customer')
  })

  it('attaches the bearer token when validating the current user', async () => {
    let requestUrl = ''
    let authorization = ''
    const fetcher: typeof fetch = async (input, init) => {
      requestUrl = String(input)
      authorization = new Headers(init?.headers).get('Authorization') ?? ''
      return jsonResponse({
        userId: 'user-id',
        phone: '9876543210',
        role: 'CUSTOMER',
      })
    }

    const user = await getCurrentUser('test-token', {
      apiBase: '/api',
      fetcher,
    })

    assert.equal(requestUrl, '/api/auth/me')
    assert.equal(authorization, 'Bearer test-token')
    assert.equal(user.role, 'CUSTOMER')
  })

  it('keeps 403 distinct from expired or missing authentication', async () => {
    const fetcher: typeof fetch = async () => jsonResponse({ error: 'Forbidden' }, 403)

    await assert.rejects(
      apiRequest('/owner/customers', 'test-token', undefined, { fetcher }),
      (error: unknown) => error instanceof ApiError
        && error.status === 403
        && error.message.includes("don't have permission"),
    )
    assert.equal(isUnauthenticatedError(new ApiError('expired', 401)), true)
    assert.equal(isUnauthenticatedError(new ApiError('forbidden', 403)), false)
  })

  it('reports a 401 API request as an expired session', async () => {
    const fetcher: typeof fetch = async () => jsonResponse({ error: 'Unauthorized' }, 401)

    await assert.rejects(
      apiRequest('/owner/bills', 'expired-token', undefined, { fetcher }),
      (error: unknown) => error instanceof ApiError
        && error.status === 401
        && error.message.includes('session has expired'),
    )
  })

  it('surfaces network failures during current-user validation', async () => {
    const fetcher: typeof fetch = async () => {
      throw new TypeError('Failed to fetch')
    }

    await assert.rejects(getCurrentUser('test-token', { fetcher }), TypeError)
  })
})

export type UserRole = 'OWNER' | 'CUSTOMER'

export type LoginResponse = {
  token: string
  userId: string
  phone: string
  role: UserRole
}

export type AuthenticatedUser = {
  userId: string
  phone: string
  role: UserRole
}

export function isOwner(user: Pick<AuthenticatedUser, 'role'>) {
  return user.role === 'OWNER'
}

export function workspaceForRole(role: UserRole): 'owner' | 'customer' {
  return role === 'OWNER' ? 'owner' : 'customer'
}

export const TOKEN_KEY = 'milk-web-owner-token'

type SessionStoragePort = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export function readSessionToken(storage: SessionStoragePort = sessionStorage) {
  return storage.getItem(TOKEN_KEY) ?? ''
}

export function storeSessionToken(token: string, storage: SessionStoragePort = sessionStorage) {
  storage.setItem(TOKEN_KEY, token)
}

export function clearSessionToken(storage: SessionStoragePort = sessionStorage) {
  storage.removeItem(TOKEN_KEY)
}

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export function isUnauthenticatedError(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401
}

type ApiOptions = {
  fetcher?: typeof fetch
  apiBase?: string
  fallback?: string
  operation?: 'login' | 'request'
}

function apiBaseUrl() {
  return import.meta.env?.VITE_API_BASE_URL ?? '/api'
}

async function readResponseBody(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}

function bodyMessage(body: unknown) {
  if (typeof body === 'string' && body.trim() && !body.trimStart().startsWith('<')) {
    return body
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return null
  const candidates = [
    'error' in body ? body.error : undefined,
    'detail' in body ? body.detail : undefined,
    'message' in body ? body.message : undefined,
  ]
  return candidates.find((value): value is string => typeof value === 'string') ?? null
}

function isLoginResponse(body: unknown): body is LoginResponse {
  return typeof body === 'object' && body !== null
    && 'token' in body && typeof body.token === 'string' && body.token.length > 0
    && 'userId' in body && typeof body.userId === 'string' && body.userId.length > 0
    && 'phone' in body && typeof body.phone === 'string'
    && 'role' in body && (body.role === 'OWNER' || body.role === 'CUSTOMER')
}

function isAuthenticatedUser(body: unknown): body is AuthenticatedUser {
  return typeof body === 'object' && body !== null
    && 'userId' in body && typeof body.userId === 'string'
    && 'phone' in body && typeof body.phone === 'string'
    && 'role' in body && (body.role === 'OWNER' || body.role === 'CUSTOMER')
}

export async function apiRequest<T>(
  path: string,
  token?: string,
  init?: RequestInit,
  options: ApiOptions = {},
): Promise<T> {
  const headers = new Headers(init?.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init?.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await (options.fetcher ?? fetch)(
    `${options.apiBase ?? apiBaseUrl()}${path}`,
    { ...init, headers },
  )
  const body = response.status === 204 ? null : await readResponseBody(response)
  if (!response.ok) {
    const message = response.status === 401
      ? options.operation === 'login'
        ? 'Phone number or password is incorrect.'
        : 'Your session has expired. Please sign in again.'
      : response.status === 403
        ? "You don't have permission to access this workspace or perform this action."
        : bodyMessage(body) ?? options.fallback ?? `Request failed (${response.status})`
    throw new ApiError(message, response.status)
  }
  if (response.status !== 204 && (body === null || typeof body === 'string')) {
    throw new ApiError('The server returned an invalid response.', response.status)
  }
  return body as T
}

export async function login(
  phone: string,
  password: string,
  options: ApiOptions = {},
): Promise<LoginResponse> {
  if (!/^\d{10}$/.test(phone) || !password.trim()) {
    throw new ApiError('Enter a valid 10-digit phone number and password.', 400)
  }
  const body = await apiRequest<unknown>(
    '/auth/login',
    undefined,
    { method: 'POST', body: JSON.stringify({ phone, password }) },
    { ...options, fallback: 'Sign in failed.', operation: 'login' },
  )
  if (!isLoginResponse(body)) {
    throw new ApiError('The server returned an invalid login response.', 200)
  }
  return body
}

export async function getCurrentUser(
  token: string,
  options: ApiOptions = {},
): Promise<AuthenticatedUser> {
  const body = await apiRequest<unknown>(
    '/auth/me',
    token,
    undefined,
    { ...options, fallback: 'Could not validate your session.' },
  )
  if (!isAuthenticatedUser(body)) {
    throw new ApiError('The server returned an invalid session response.', 200)
  }
  return body
}

export type RegistrationDetails = {
  phone: string
  password: string
  name: string
  address: string
}

const MAX_PASSWORD_BYTES = 72

// Mirrors RegisterCustomerRequest on the backend so most mistakes are caught before a request is sent.
// The server still validates everything; this is a usability check, not a security control.
export function registrationProblem(details: RegistrationDetails): string | null {
  if (!/^\d{10}$/.test(details.phone)) return 'Enter a valid 10-digit phone number.'
  if (!details.name.trim()) return 'Enter your name.'
  if (details.name.trim().length > 100) return 'Name must be at most 100 characters.'
  if (details.password.length < 8) return 'Password must be at least 8 characters.'
  if (new TextEncoder().encode(details.password).length > MAX_PASSWORD_BYTES) {
    return 'Password is too long. Use at most 72 characters.'
  }
  if (details.address.trim().length > 500) return 'Address must be at most 500 characters.'
  return null
}

export async function registerCustomer(
  details: RegistrationDetails,
  options: ApiOptions = {},
): Promise<LoginResponse> {
  const problem = registrationProblem(details)
  if (problem) throw new ApiError(problem, 400)
  const address = details.address.trim()
  const body = await apiRequest<unknown>(
    '/auth/register',
    undefined,
    {
      method: 'POST',
      body: JSON.stringify({
        phone: details.phone,
        password: details.password,
        name: details.name.trim(),
        address: address || null,
      }),
    },
    { ...options, fallback: 'Could not create your account.' },
  )
  if (!isLoginResponse(body)) {
    throw new ApiError('The server returned an invalid registration response.', 200)
  }
  return body
}

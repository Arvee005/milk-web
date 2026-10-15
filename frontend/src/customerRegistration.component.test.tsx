import assert from 'node:assert/strict'
import { afterEach, describe, it } from 'node:test'
import { JSDOM } from 'jsdom'
import { createElement } from 'react'
import { ApiError, type LoginResponse, type RegistrationDetails } from './authApi.ts'
import CustomerRegistration from './CustomerRegistration.tsx'

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

const signedIn: LoginResponse = { token: 'new-token', userId: 'user-id', phone: '9123456780', role: 'CUSTOMER' }

function showForm(register: (details: RegistrationDetails) => Promise<LoginResponse>) {
  const registered: LoginResponse[] = []
  let signInShown = 0
  render(createElement(CustomerRegistration, {
    register,
    onRegistered: (result: LoginResponse) => registered.push(result),
    onShowSignIn: () => { signInShown += 1 },
  }))
  return { registered, signInShown: () => signInShown }
}

function fill(values: Partial<Record<'phone' | 'name' | 'address' | 'password' | 'confirm', string>>) {
  const all = { phone: '9123456780', name: 'Asha Devi', address: '', password: 'customer-pass', confirm: 'customer-pass', ...values }
  fireEvent.change(screen.getByLabelText('Phone number'), { target: { value: all.phone } })
  fireEvent.change(screen.getByLabelText('Full name'), { target: { value: all.name } })
  fireEvent.change(screen.getByLabelText(/^Address/), { target: { value: all.address } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: all.password } })
  fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: all.confirm } })
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
}

describe('customer registration form', () => {
  afterEach(() => cleanup())

  it('registers with the entered details and hands the session to the app', async () => {
    const calls: RegistrationDetails[] = []
    const form = showForm(async (details) => {
      calls.push(details)
      return signedIn
    })
    fill({ address: 'Near the temple' })
    submit()

    await waitFor(() => assert.equal(form.registered.length, 1))
    assert.deepEqual(calls, [{
      phone: '9123456780', name: 'Asha Devi', address: 'Near the temple', password: 'customer-pass',
    }])
    assert.equal(form.registered[0].token, 'new-token')
  })

  it('keeps only digits in the phone field', () => {
    showForm(async () => signedIn)
    fireEvent.change(screen.getByLabelText('Phone number'), { target: { value: '91-2345 678ab90' } })
    assert.equal((screen.getByLabelText('Phone number') as HTMLInputElement).value, '9123456789')
  })

  it('blocks mismatched passwords without calling the server', async () => {
    let called = false
    showForm(async () => {
      called = true
      return signedIn
    })
    fill({ confirm: 'different-pass' })
    submit()

    assert.match((await screen.findByRole('alert')).textContent ?? '', /do not match/)
    assert.equal(called, false)
  })

  it('blocks a short password and a missing name', async () => {
    showForm(async () => signedIn)
    fill({ password: 'short', confirm: 'short' })
    submit()
    assert.match((await screen.findByRole('alert')).textContent ?? '', /at least 8/)

    fill({ name: '  ' })
    submit()
    assert.match((await screen.findByRole('alert')).textContent ?? '', /name/)
  })

  it('shows a duplicate-phone conflict and keeps the entered details', async () => {
    const form = showForm(async () => {
      throw new ApiError('Phone number already registered: 9123456780', 409)
    })
    fill({})
    submit()

    assert.match((await screen.findByRole('alert')).textContent ?? '', /already registered/)
    assert.equal(form.registered.length, 0)
    assert.equal((screen.getByLabelText('Full name') as HTMLInputElement).value, 'Asha Devi')
  })

  it('explains a network failure', async () => {
    showForm(async () => {
      throw new TypeError('Failed to fetch')
    })
    fill({})
    submit()

    assert.match((await screen.findByRole('alert')).textContent ?? '', /Unable to connect/)
  })

  it('returns to sign in', () => {
    const form = showForm(async () => signedIn)
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    assert.equal(form.signInShown(), 1)
  })
})

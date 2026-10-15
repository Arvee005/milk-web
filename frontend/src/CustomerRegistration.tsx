import { useState, type FormEvent } from 'react'
import { registerCustomer, registrationProblem, type LoginResponse } from './authApi'

type CustomerRegistrationProps = {
  onRegistered: (result: LoginResponse) => void
  onShowSignIn: () => void
  register?: typeof registerCustomer
}

export default function CustomerRegistration({
  onRegistered,
  onShowSignIn,
  register = registerCustomer,
}: CustomerRegistrationProps) {
  const [phone, setPhone] = useState('')
  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) return
    const details = { phone, name, address, password }
    const problem = registrationProblem(details)
    if (problem) {
      setError(problem)
      return
    }
    if (password !== confirmPassword) {
      setError('The two passwords do not match.')
      return
    }
    setSubmitting(true)
    setError('')
    try {
      const result = await register(details)
      setPassword('')
      setConfirmPassword('')
      onRegistered(result)
    } catch (cause) {
      setError(cause instanceof TypeError
        ? 'Unable to connect to Radhika Farms. Check your connection and try again.'
        : cause instanceof Error ? cause.message : 'Could not create your account.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <p className="eyebrow">NEW CUSTOMER</p>
      <h2>Create your customer account.</h2>
      <p className="login-copy">
        Use the phone number the dairy knows you by. The owner will approve your account before
        your milk records appear.
      </p>
      <form onSubmit={submit} className="login-form" aria-label="Create a customer account" noValidate>
        <label htmlFor="register-phone">Phone number</label>
        <input id="register-phone" type="tel" inputMode="numeric" autoComplete="tel-national"
          maxLength={10} value={phone}
          onChange={(event) => setPhone(event.target.value.replace(/\D/g, '').slice(0, 10))}
          aria-describedby="register-phone-help" required />
        <span id="register-phone-help" className="form-hint">10 digits. You will sign in with this number.</span>
        <label htmlFor="register-name">Full name</label>
        <input id="register-name" type="text" autoComplete="name" maxLength={100} value={name}
          onChange={(event) => setName(event.target.value)} required />
        <label htmlFor="register-address">Address <span className="form-hint">(optional)</span></label>
        <input id="register-address" type="text" autoComplete="street-address" maxLength={500} value={address}
          onChange={(event) => setAddress(event.target.value)} />
        <label htmlFor="register-password">Password</label>
        <input id="register-password" type="password" autoComplete="new-password" value={password}
          onChange={(event) => setPassword(event.target.value)} aria-describedby="register-password-help" required />
        <span id="register-password-help" className="form-hint">At least 8 characters.</span>
        <label htmlFor="register-confirm-password">Confirm password</label>
        <input id="register-confirm-password" type="password" autoComplete="new-password" value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)} required />
        {error && <p className="alert error-alert" role="alert">{error}</p>}
        <button className="primary-button full-button" disabled={submitting} aria-busy={submitting}>
          {submitting ? 'Creating account…' : 'Create account'}
        </button>
      </form>
      <p className="auth-switch">
        Already have an account?{' '}
        <button type="button" className="text-button" onClick={onShowSignIn}>Sign in</button>
      </p>
    </>
  )
}

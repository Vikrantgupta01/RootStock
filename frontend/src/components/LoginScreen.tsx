import { useState } from 'react'
import { auth } from '../api/auth'
import type { ApiError } from '../api/http'
import { HealthBadge } from './HealthBadge'

/**
 * The only screen reachable without a token. Credentials go to the backend,
 * which exchanges them with Cognito and hands back the tokens.
 */
export function LoginScreen() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await auth.login(email.trim(), password)
      // No navigation needed: the session change re-renders App, which now has
      // a token and renders the real routes.
    } catch (err) {
      setError((err as ApiError).detail ?? 'Sign in failed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="page page--narrow">
      <div className="page__header">
        <span className="brand">
          <span className="brand__dot" aria-hidden="true" />
          RootStock
        </span>
        <HealthBadge />
      </div>

      <form className="card stack login" onSubmit={submit}>
        <h2>Sign in</h2>
        <p className="login__lead">Use the account your administrator created for you.</p>
        <label className="field">
          Email
          <input
            type="email"
            value={email}
            autoComplete="username"
            required
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label className="field">
          Password
          <input
            type="password"
            value={password}
            autoComplete="current-password"
            required
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <p className="error-text">{error}</p>}
        <button className="btn" type="submit" disabled={busy || !email || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  )
}

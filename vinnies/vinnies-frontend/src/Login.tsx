import { useState, type FormEvent } from 'react'
import { api, type ApiError } from './api'

export function Login() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.login(email, password)
    } catch (err) {
      setError((err as ApiError).detail ?? 'Sign-in failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="login" onSubmit={submit}>
      <h1>Vinnies case review</h1>
      <p className="muted">For coordinators. Fictional demo data.</p>
      <label>
        Email
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required />
      </label>
      <label>
        Password
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          required
        />
      </label>
      {error && <div className="error">{error}</div>}
      <button className="primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
    </form>
  )
}

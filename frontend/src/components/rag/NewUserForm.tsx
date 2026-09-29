import { useState } from 'react'
import type { Role } from '../../api/auth'
import type { ApiError } from '../../api/http'
import { useCreateUser } from '../../hooks/access'

const ROLES: { value: Role; hint: string }[] = [
  { value: 'VIEWER', hint: 'query and browse only' },
  { value: 'EDITOR', hint: 'also upload, reindex and delete documents' },
  { value: 'ADMIN', hint: 'also tune profiles and manage access; sees every document' },
]

/**
 * Creates a user in this admin's own tenant. There is no tenant field on
 * purpose: the backend takes it from the caller's token, so an admin cannot
 * create users anywhere but the tenant they already administer.
 */
export function NewUserForm({ groups }: { groups: string[] }) {
  const create = useCreateUser()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<Role>('VIEWER')
  const [selected, setSelected] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<string | null>(null)

  function toggle(group: string) {
    setSelected((s) => (s.includes(group) ? s.filter((g) => g !== group) : [...s, group]))
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setCreated(null)
    try {
      const user = await create.mutateAsync({ email: email.trim(), password, role, groups: selected })
      setCreated(user.email)
      setEmail('')
      setPassword('')
      setSelected([])
    } catch (err) {
      setError((err as ApiError).detail ?? 'Could not create the user.')
    }
  }

  return (
    <form className="card stack" onSubmit={submit}>
      <h3>New user</h3>
      <label className="field">
        Email
        <input type="email" value={email} required onChange={(e) => setEmail(e.target.value)} />
      </label>
      <label className="field">
        Password
        <input
          type="text"
          value={password}
          required
          autoComplete="off"
          onChange={(e) => setPassword(e.target.value)}
        />
        <span className="muted small">
          Set as a permanent password so they can sign in immediately — pass it on out of band.
          The user pool requires at least 12 characters with upper and lower case, a digit and a symbol.
        </span>
      </label>
      <label className="field">
        Role
        <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {ROLES.map((r) => (
            <option key={r.value} value={r.value}>
              {r.value} — {r.hint}
            </option>
          ))}
        </select>
      </label>
      {groups.length > 0 && (
        <div className="field">
          Access groups
          <div className="pill-list">
            {groups.map((g) => (
              <label key={g} className={`pill pill--toggle${selected.includes(g) ? ' pill--on' : ''}`}>
                <input type="checkbox" checked={selected.includes(g)} onChange={() => toggle(g)} />
                {g}
              </label>
            ))}
          </div>
        </div>
      )}
      {error && <p className="error-text">{error}</p>}
      {created && <p className="muted small">Created {created}.</p>}
      <button className="btn" type="submit" disabled={create.isPending || !email.trim() || !password}>
        {create.isPending ? 'Creating…' : 'Create user'}
      </button>
      <p className="muted small">
        Created in this tenant only. The first admin of a <em>new</em> tenant still has to be
        seeded with the AWS CLI — nothing here could authorize that.
      </p>
    </form>
  )
}

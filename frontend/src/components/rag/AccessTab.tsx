import { useState } from 'react'
import type { ApiError } from '../../api/http'
import { useAccessGroups, useCreateAccessGroup } from '../../hooks/access'

/**
 * Creates the groups documents can be restricted to. Membership is not managed
 * here: who belongs to a group is a Cognito user-pool concern, and the app reads
 * it off each request's token rather than storing it.
 */
export function AccessTab() {
  const groups = useAccessGroups()
  const create = useCreateAccessGroup()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await create.mutateAsync({ name: name.trim(), description: description.trim() || undefined })
      setName('')
      setDescription('')
    } catch (err) {
      setError((err as ApiError).detail ?? 'Could not create the group.')
    }
  }

  return (
    <div className="stack">
      <p className="muted">
        A document tagged with one or more groups is only retrievable by users in those groups.
        A document with no groups stays visible to everyone in the tenant. Admins see everything
        either way. Tag documents on the Documents tab.
      </p>

      <form className="card stack" onSubmit={submit}>
        <h3>New group</h3>
        <label className="field">
          Name
          <input
            value={name}
            placeholder="hr-only"
            onChange={(e) => setName(e.target.value)}
            required
          />
        </label>
        <label className="field">
          Description
          <input value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
        {error && <p className="error-text">{error}</p>}
        <button className="btn" type="submit" disabled={create.isPending || !name.trim()}>
          {create.isPending ? 'Creating…' : 'Create group'}
        </button>
        <p className="muted small">
          Created in the Cognito user pool as well as here. Add users to it from the Cognito console.
        </p>
      </form>

      {groups.isLoading && <p className="muted">Loading groups…</p>}
      {groups.data && groups.data.length === 0 && <p className="muted">No groups yet.</p>}
      {groups.data && groups.data.length > 0 && (
        <ul className="pill-list">
          {groups.data.map((g) => (
            <li key={g} className="pill">
              {g}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

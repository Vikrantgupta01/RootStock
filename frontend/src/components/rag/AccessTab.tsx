import { useState } from 'react'
import type { ApiError } from '../../api/http'
import { useAccessGroups, useCreateAccessGroup } from '../../hooks/access'
import { NewUserForm } from './NewUserForm'

/**
 * Access administration: the groups documents can be restricted to, and the
 * users who belong to them. Two panels side by side rather than stacked forms,
 * so the groups you just created are visible while you assign them.
 *
 * <p>Group <em>membership</em> isn't edited here — that's a Cognito user-pool
 * concern, and the app reads it off each request's token rather than storing it.
 * A user's initial groups are set when they're created.
 */
export function AccessTab() {
  const groups = useAccessGroups()

  return (
    <div className="stack">
      <p className="muted">
        A document tagged with one or more groups is only retrievable by members of those groups.
        A document with no groups stays visible to everyone in the tenant, and admins see
        everything either way. Tag documents on the Documents tab.
      </p>

      <div className="panel-grid">
        <GroupsPanel names={groups.data ?? []} loading={groups.isLoading} />
        <NewUserForm groups={groups.data ?? []} />
      </div>
    </div>
  )
}

function GroupsPanel({ names, loading }: { names: string[]; loading: boolean }) {
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
    <form className="panel" onSubmit={submit}>
      <div className="panel__head">
        <h3 className="panel__title">
          Access groups
          {!loading && <span className="panel__count">{names.length}</span>}
        </h3>
        <p className="panel__hint">Created in the Cognito user pool as well as here.</p>
      </div>

      <div className="panel__body">
        {loading && <p className="empty-note">Loading…</p>}
        {!loading && names.length === 0 && <p className="empty-note">No groups yet.</p>}
        {names.length > 0 && (
          <ul className="group-list">
            {names.map((g) => (
              <li key={g} className="pill">
                {g}
              </li>
            ))}
          </ul>
        )}

        <div className="field-row">
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
            <input
              value={description}
              placeholder="optional"
              onChange={(e) => setDescription(e.target.value)}
            />
          </label>
        </div>
        {error && <p className="error-text">{error}</p>}
      </div>

      <div className="form-foot">
        <p className="form-foot__note">Lowercase letters, digits, hyphen or underscore.</p>
        <button className="btn btn--primary" type="submit" disabled={create.isPending || !name.trim()}>
          {create.isPending ? 'Adding…' : 'Add group'}
        </button>
      </div>
    </form>
  )
}

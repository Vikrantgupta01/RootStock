import { useCurrentUser, useSignOut } from '../hooks/useSession'

/**
 * Replaces the old free-text tenant field: the tenant is now whatever the
 * verified token says, so it is shown rather than chosen.
 */
export function IdentityBar() {
  const user = useCurrentUser()
  const signOut = useSignOut()

  if (!user.data) return null
  const { email, tenantId, role, groups } = user.data

  return (
    <div className="identity-bar">
      <span className="identity-bar__who">
        <strong>{email ?? 'signed in'}</strong>
        <span className="pill">{role}</span>
      </span>
      <span className="muted small">
        tenant <code>{tenantId}</code>
        {groups.length > 0 && <> · groups {groups.map((g) => <code key={g}> {g}</code>)}</>}
      </span>
      <button className="btn btn--ghost btn--sm" onClick={signOut}>
        sign out
      </button>
    </div>
  )
}

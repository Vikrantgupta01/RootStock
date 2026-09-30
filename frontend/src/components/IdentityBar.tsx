import { useCurrentUser } from '../hooks/useSession'

/**
 * Who the backend thinks you are. This replaced a free-text tenant field: the
 * tenant comes from the verified token now, so it is shown, not chosen.
 */
export function IdentityBar() {
  const user = useCurrentUser()

  if (!user.data) return null
  const { email, tenantId, role, groups } = user.data

  return (
    <div className="identity-bar">
      <span className="identity-bar__who">
        <strong>{email ?? 'signed in'}</strong>
        <span className="pill">{role}</span>
      </span>
      <span className="identity-bar__meta">
        tenant {tenantId}
        {groups.length > 0 && ` · groups ${groups.join(', ')}`}
      </span>
    </div>
  )
}

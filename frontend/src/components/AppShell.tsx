import type { ReactNode } from 'react'
import { Link, NavLink } from 'react-router-dom'
import { HealthBadge } from './HealthBadge'
import { hasRole, useCurrentUser, useSignOut } from '../hooks/useSession'

/**
 * The frame every signed-in page sits in: brand, primary navigation, backend
 * health and sign-out. Previously each page drew its own header, so moving
 * between Chat and the knowledge base meant using the browser's back button and
 * sign-out only existed on one of them.
 */
export function AppShell({ children, wide = false }: { children: ReactNode; wide?: boolean }) {
  const user = useCurrentUser()
  const signOut = useSignOut()

  return (
    <>
      <header className="appbar">
        <div className="appbar__inner">
          <Link to="/" className="brand">
            <span className="brand__dot" aria-hidden="true" />
            RootStock
          </Link>
          <nav className="appnav">
            <NavLink to="/" end className={({ isActive }) => (isActive ? 'is-active' : '')}>
              Chat
            </NavLink>
            <NavLink to="/agent" className={({ isActive }) => (isActive ? 'is-active' : '')}>
              Agent
            </NavLink>
            <NavLink to="/knowledge" className={({ isActive }) => (isActive ? 'is-active' : '')}>
              Knowledge base
            </NavLink>
            {hasRole(user.data, 'ADMIN') && (
              <NavLink to="/tools" className={({ isActive }) => (isActive ? 'is-active' : '')}>
                Tools
              </NavLink>
            )}
          </nav>
          <div className="appbar__side">
            <HealthBadge />
            {user.data && (
              <button className="btn btn--ghost btn--sm" onClick={signOut}>
                sign out
              </button>
            )}
          </div>
        </div>
      </header>
      <main className={wide ? 'page page--wide' : 'page'}>{children}</main>
    </>
  )
}

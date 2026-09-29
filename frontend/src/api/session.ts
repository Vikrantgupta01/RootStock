// Where the signed-in user's Cognito tokens live between page loads.
//
// The backend derives tenant, role and groups from the ID token's claims, so the
// token is the whole client-side session -- there is nothing else to keep, and
// nothing the client can assert on its own.

export interface Session {
  idToken: string
  /** Absent after a refresh: Cognito reuses the refresh token it already issued. */
  refreshToken: string | null
  /** Epoch millis the ID token expires at, used to refresh slightly early. */
  expiresAt: number
}

const KEY = 'rootstock.session'
const EVENT = 'rootstock:session-changed'

export function getSession(): Session | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const session = JSON.parse(raw) as Session
    return session.idToken ? session : null
  } catch {
    return null
  }
}

export function setSession(session: Session): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(session))
  } catch {
    // ignore: storage may be unavailable (private mode, blocked site data)
  }
  window.dispatchEvent(new CustomEvent(EVENT))
}

export function clearSession(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // ignore
  }
  window.dispatchEvent(new CustomEvent(EVENT))
}

export function onSessionChange(listener: () => void): () => void {
  window.addEventListener(EVENT, listener)
  return () => window.removeEventListener(EVENT, listener)
}

/** The Authorization header for a request, or nothing when signed out. */
export function authHeaders(): Record<string, string> {
  const session = getSession()
  return session ? { Authorization: `Bearer ${session.idToken}` } : {}
}

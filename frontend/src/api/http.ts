// Shared fetch wrapper. In dev, VITE_API_BASE_URL is empty and requests go to
// "/api/...", proxied to the backend by the Vite dev server.

import { clearSession, getSession, setSession } from './session'

export const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export interface ApiError {
  status: number
  title: string
  detail: string
}

interface Options {
  /** Send no Authorization header -- only login and refresh, which have no token yet. */
  anonymous?: boolean
}

export async function request<T>(path: string, init: RequestInit = {}, opts: Options = {}): Promise<T> {
  let res = await send(path, init, !opts.anonymous)

  // An expired ID token is the common case, not an error: swap it for a fresh
  // one and retry once. A 401 that survives that means the session is really
  // gone, and clearing it drops the UI back to the login screen.
  if (res.status === 401 && !opts.anonymous) {
    if (await refreshOnce()) {
      res = await send(path, init, true)
    }
    if (res.status === 401) {
      clearSession()
    }
  }

  if (!res.ok) {
    throw await toApiError(res)
  }
  if (res.status === 204) {
    return undefined as T
  }
  return (await res.json()) as T
}

function send(path: string, init: RequestInit, authenticated: boolean): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (authenticated) {
    const session = getSession()
    if (session) {
      headers.set('Authorization', `Bearer ${session.idToken}`)
    }
  }
  return fetch(`${BASE_URL}${path}`, { ...init, headers })
}

let inFlightRefresh: Promise<boolean> | null = null

/** Single-flight: several requests failing at once must not each spend the refresh token. */
function refreshOnce(): Promise<boolean> {
  if (!inFlightRefresh) {
    inFlightRefresh = doRefresh().finally(() => {
      inFlightRefresh = null
    })
  }
  return inFlightRefresh
}

async function doRefresh(): Promise<boolean> {
  const session = getSession()
  if (!session?.refreshToken) return false
  try {
    const res = await fetch(`${BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    })
    if (!res.ok) return false
    const body = (await res.json()) as { idToken: string; expiresInSeconds: number }
    setSession({
      idToken: body.idToken,
      refreshToken: session.refreshToken,
      expiresAt: Date.now() + body.expiresInSeconds * 1000,
    })
    return true
  } catch {
    return false
  }
}

export async function toApiError(res: Response): Promise<ApiError> {
  let detail = res.statusText
  let title = 'Request failed'
  try {
    const body = (await res.json()) as Partial<ApiError>
    detail = body.detail ?? detail
    title = body.title ?? title
  } catch {
    // no JSON body
  }
  return { status: res.status, title, detail }
}

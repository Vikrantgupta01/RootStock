// Rootstock's API, as the Vinnies coordinators' app uses it: sign in (Rootstock
// signs in through the same Cognito pool), list the cases waiting for this
// coordinator, read one, and post their decision. Rootstock checks who may decide.

export interface ApiError {
  status: number
  detail: string
}

interface Session {
  idToken: string
  refreshToken: string | null
}

const KEY = 'vinnies.session'
const listeners = new Set<() => void>()

export function getSession(): Session | null {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

function setSession(session: Session | null) {
  try {
    if (session) localStorage.setItem(KEY, JSON.stringify(session))
    else localStorage.removeItem(KEY)
  } catch {
    // storage unavailable: the session lasts as long as the page
  }
  listeners.forEach((l) => l())
}

export function onSessionChange(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

async function send(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined) headers.set('Content-Type', 'application/json')
  const session = getSession()
  if (session) headers.set('Authorization', `Bearer ${session.idToken}`)
  return fetch(path, { ...init, headers })
}

async function refresh(): Promise<boolean> {
  const session = getSession()
  if (!session?.refreshToken) return false
  const res = await fetch('/api/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  })
  if (!res.ok) return false
  const body = (await res.json()) as { idToken: string }
  setSession({ idToken: body.idToken, refreshToken: session.refreshToken })
  return true
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res = await send(path, init)
  if (res.status === 401 && (await refresh())) res = await send(path, init)
  if (res.status === 401) setSession(null)
  if (!res.ok) {
    let detail = res.statusText
    try {
      detail = ((await res.json()) as { detail?: string }).detail ?? detail
    } catch {
      // no JSON body
    }
    throw { status: res.status, detail } satisfies ApiError
  }
  return (await res.json()) as T
}

// ---- types (as Rootstock returns them) -----------------------------------------

export interface Me {
  userId: string
  email: string | null
  role: string
  groups: string[]
}

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json }

export interface Issue {
  ruleId: string
  severity: 'BLOCKING' | 'WARNING'
  answerableBy: 'SUBMITTER' | 'EXTERNAL' | 'REVIEWER'
  message: string
  path: string | null
  layer: string | null
}

export interface Action {
  type: string
  summary: string
  details: Record<string, Json>
}

export interface CaseRun {
  caseId: string
  pack: string
  status: string
  pause: { node: string; before: boolean } | null
  /** The roles that may decide it, while it waits for a decision. */
  waitingFor: string[] | null
  startedAt: string
  traceUrl: string | null
  input: string | null
  result: {
    record?: Record<string, Json>
    issues?: Issue[]
    actions?: Action[]
    context?: { summary?: string; lookups?: { tool: string; status: string; result: Json }[] }
    review?: { decision: string; byEmail: string | null; comment: string | null }
  } | null
}

export interface Projection {
  schema: JsonSchema
  glossary: string
}

export interface JsonSchema {
  type?: string | string[]
  enum?: (string | null)[]
  format?: string
  description?: string
  title?: string
  properties?: Record<string, JsonSchema>
  required?: string[]
  items?: JsonSchema
  $ref?: string
  anyOf?: JsonSchema[]
  $defs?: Record<string, JsonSchema>
}

export type Decision = 'APPROVED' | 'EDITED' | 'REJECTED'

export const api = {
  async login(email: string, password: string) {
    const body = await request<{ idToken: string; refreshToken: string | null }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    setSession({ idToken: body.idToken, refreshToken: body.refreshToken })
  },
  logout: () => setSession(null),
  me: () => request<Me>('/api/auth/me'),
  awaiting: () => request<CaseRun[]>('/api/cases/awaiting-decision'),
  case: (caseId: string) => request<CaseRun>(`/api/cases/${encodeURIComponent(caseId)}`),
  /** The record's schema, as a reviewer edits it (the ontology's extraction view). */
  projection: (pack: string, projection: string) =>
    request<Projection>(
      `/api/ontology/packs/${encodeURIComponent(pack)}/projections/${encodeURIComponent(projection)}?mode=extraction`,
    ),
  decide: (caseId: string, decision: Decision, comment: string, record?: Record<string, Json>) =>
    request<CaseRun>(`/api/cases/${encodeURIComponent(caseId)}/decision`, {
      method: 'POST',
      body: JSON.stringify({ decision, comment, record }),
    }),
}

// Minimal typed fetch wrapper for the RootStock backend.
//
// In development, VITE_API_BASE_URL is empty and requests go to "/api/...",
// which the Vite dev server proxies to the backend. In other environments set
// VITE_API_BASE_URL to the backend origin.

const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export interface ApiError {
  status: number
  title: string
  detail: string
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })

  if (!res.ok) {
    let detail = res.statusText
    let title = 'Request failed'
    try {
      const body = (await res.json()) as Partial<ApiError>
      detail = body.detail ?? detail
      title = body.title ?? title
    } catch {
      // response had no JSON body; keep the status text
    }
    const error: ApiError = { status: res.status, title, detail }
    throw error
  }

  return (await res.json()) as T
}

export interface HealthResponse {
  status: string
  app: string
  version: string
  timestamp: string
}

export interface ChatResponse {
  reply: string
}

export const api = {
  health: () => request<HealthResponse>('/api/health'),
  chat: (message: string) =>
    request<ChatResponse>('/api/chat', {
      method: 'POST',
      body: JSON.stringify({ message }),
    }),
}

// Shared fetch wrapper. In dev, VITE_API_BASE_URL is empty and requests go to
// "/api/...", proxied to the backend by the Vite dev server.

import { getTenant } from './tenant'

export const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export interface ApiError {
  status: number
  title: string
  detail: string
}

interface Options {
  /** send the current X-Tenant-Id header (RAG endpoints) */
  tenant?: boolean
}

export async function request<T>(path: string, init: RequestInit = {}, opts: Options = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (opts.tenant) {
    headers.set('X-Tenant-Id', getTenant())
  }

  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers })

  if (!res.ok) {
    throw await toApiError(res)
  }
  if (res.status === 204) {
    return undefined as T
  }
  return (await res.json()) as T
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

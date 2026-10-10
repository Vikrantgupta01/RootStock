// Platform status: is every service Rootstock depends on answering right now.

import { request } from './http'

export interface StatusCheck {
  name: string
  ok: boolean
  /** What was checked and found, or why it failed. */
  detail: string
  latencyMs: number
  /** Where to look further (the latest Langfuse trace); null when there is none. */
  link: string | null
}

export interface PlatformStatus {
  allOk: boolean
  checks: StatusCheck[]
}

export const statusApi = {
  check: () => request<PlatformStatus>('/api/status'),
}

// Backend client for the non-RAG endpoints. RAG lives in ./rag.

import { request } from './http'

export type { ApiError } from './http'

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

// Backend client for the non-RAG endpoints. RAG lives in ./rag.

import { conversations } from './conversation'
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
  /** Send this back with the next message to continue the same thread. */
  conversationId: string
}

export const api = {
  health: () => request<HealthResponse>('/api/health'),
  chat: (message: string, conversationId?: string | null) =>
    request<ChatResponse>('/api/chat', {
      method: 'POST',
      body: JSON.stringify({ message, conversationId: conversationId ?? null }),
    }),
  conversations: conversations('/api/chat'),
}

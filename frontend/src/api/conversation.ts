// Conversation threads, shared by the plain chat, the RAG playground and the agent. The
// backend owns the history; the client only ever holds the thread's id.

import { request } from './http'

export interface ConversationSummary {
  id: string
  title: string | null
  createdAt: string
  updatedAt: string
}

export interface ConversationMessage {
  id: string
  seq: number
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
}

/** `base` is '/api/chat', '/api/rag' or '/api/agent' -- each keeps its own threads. */
export function conversations(base: string) {
  return {
    list: () => request<ConversationSummary[]>(`${base}/conversations`),
    transcript: (id: string) => request<ConversationMessage[]>(`${base}/conversations/${id}`),
    remove: (id: string) => request<void>(`${base}/conversations/${id}`, { method: 'DELETE' }),
  }
}

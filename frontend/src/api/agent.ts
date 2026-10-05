// The ReAct agent: the model decides which tools to call, and the response
// says what it did on the way to the answer.

import { conversations } from './conversation'
import { request } from './http'

/** One Act/Observe step. Several calls requested together share one `thought`. */
export interface AgentStep {
  /** Which Reason step asked for this call, from 1. */
  iteration: number
  /** What the model said alongside the call; only on the first call of an iteration. */
  thought: string | null
  tool: string
  /** The tool arguments as the model sent them (JSON). */
  input: string
  /** The tool's result, abbreviated -- the model saw it in full. */
  observation: string
}

export interface AgentResponse {
  answer: string
  conversationId: string
  /** Reason steps taken (model calls). */
  iterations: number
  steps: AgentStep[]
}

export const agentApi = {
  ask: (message: string, conversationId?: string | null) =>
    request<AgentResponse>('/api/agent', {
      method: 'POST',
      body: JSON.stringify({ message, conversationId: conversationId ?? null }),
    }),
  conversations: conversations('/api/agent'),
}

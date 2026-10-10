// Cases: submit input to a pack's graph and follow the run node by node.

import { request, streamEvents } from './http'

export type RunStatus = 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED'
export type RunEventType =
  | 'RUN_STARTED'
  | 'NODE_STARTED'
  | 'NODE_FINISHED'
  | 'NODE_FAILED'
  | 'RUN_PAUSED'
  | 'RUN_COMPLETED'
  | 'RUN_FAILED'
export type Simulate = 'none' | 'clarify' | 'chase'

export interface RunEvent {
  seq: number
  type: RunEventType
  /** The node it is about; null for run-level events. */
  node: string | null
  at: string
  durationMs: number | null
  detail: string | null
}

export interface CaseRun {
  caseId: string
  runId: string
  pack: string
  graph: string
  graphVersion: string | null
  status: RunStatus
  /** Where a paused run stopped: before the node (e.g. review) or after it (e.g. clarify). */
  pause: { node: string; before: boolean } | null
  error: string | null
  startedAt: string
  /** The run's Langfuse trace; null when tracing is off. */
  traceUrl: string | null
  /** Only in a single case's detail. */
  events: RunEvent[] | null
  result: Record<string, unknown> | null
}

export interface GraphNode {
  id: string
  /** The node type, or the agent's type for an agent node. */
  kind: string
  agent: string | null
  description: string | null
}

export interface GraphEdge {
  from: string
  /** label: when the branch is taken, in words; null for a plain edge. */
  branches: { to: string; label: string | null }[]
}

export interface CaseGraph {
  pack: string
  name: string
  version: string | null
  description: string | null
  nodes: GraphNode[]
  edges: GraphEdge[]
  interruptBefore: string[]
  interruptAfter: string[]
}

export interface SubmitCase {
  input: string
  simulate: Simulate
}

const RUN_ENDS: RunEventType[] = ['RUN_PAUSED', 'RUN_COMPLETED', 'RUN_FAILED']

export const isRunEnd = (event: RunEvent) => RUN_ENDS.includes(event.type)

export const casesApi = {
  graph: () => request<CaseGraph>('/api/cases/graph'),
  recent: () => request<CaseRun[]>('/api/cases'),
  detail: (caseId: string) => request<CaseRun>(`/api/cases/${encodeURIComponent(caseId)}`),
  submit: (body: SubmitCase) => request<CaseRun>('/api/cases', { method: 'POST', body: JSON.stringify(body) }),
  events: (caseId: string, onEvent: (event: RunEvent) => void, signal: AbortSignal) =>
    streamEvents<RunEvent>(`/api/cases/${encodeURIComponent(caseId)}/events`, onEvent, signal),
}

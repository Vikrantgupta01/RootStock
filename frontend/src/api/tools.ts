// Tool explorer: run a client-system tool through Rootstock's ToolGateway, as a
// chosen graph node would. Admins only.

import { request } from './http'

export type ToolAccess = 'READ' | 'WRITE'
export type ToolCallStatus = 'OK' | 'BLOCKED' | 'UNKNOWN_TOOL' | 'TOOL_ERROR' | 'UNAVAILABLE'

/** The JSON Schema a tool publishes for its input (the parts the form uses). */
export interface InputSchema {
  properties?: Record<string, { type?: string; description?: string; enum?: string[] }>
  required?: string[]
}

export interface ExplorerNode {
  name: string
  /** Tools this node may call. Anything else is refused by the gateway. */
  allowed: string[]
}

export interface ExplorerTool {
  name: string
  connection: string
  access: ToolAccess
  /** False when the client system could not be reached, or does not offer the tool. */
  available: boolean
  description: string | null
  inputSchema: InputSchema | null
  problem: string | null
}

export interface ToolsOverview {
  nodes: ExplorerNode[]
  tools: ExplorerTool[]
}

export interface ToolCallResult {
  status: ToolCallStatus
  node: string
  tool: string
  connection: string | null
  /** The tool's text result when OK. */
  output: string | null
  /** Why it did not succeed. */
  message: string | null
  durationMillis: number
}

export interface ToolCallResponse {
  result: ToolCallResult
  traceId: string | null
  /** Link to the trace in Langfuse; null when tracing is off. */
  traceUrl: string | null
}

export interface ToolCallRequest {
  node: string
  tool: string
  arguments: Record<string, unknown>
  caseId?: string
}

export const toolsApi = {
  overview: () => request<ToolsOverview>('/api/tools'),
  call: (body: ToolCallRequest) =>
    request<ToolCallResponse>('/api/tools/call', { method: 'POST', body: JSON.stringify(body) }),
}

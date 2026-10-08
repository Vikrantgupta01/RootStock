import { useMemo, useState } from 'react'
import type { ExplorerTool, InputSchema, ToolCallStatus } from '../../api/tools'
import { useToolCall, useToolsOverview } from '../../hooks/useTools'

const STATUS_BADGE: Record<ToolCallStatus, string> = {
  OK: 'badge badge--ok',
  BLOCKED: 'badge badge--busy',
  UNKNOWN_TOOL: 'badge badge--busy',
  TOOL_ERROR: 'badge badge--error',
  UNAVAILABLE: 'badge badge--error',
}

/**
 * Pick a node and a tool, fill in the tool's own input form, run it through the
 * ToolGateway. Every tool is listed, including ones the node may not call: running
 * one shows the gateway refusing it, which is the point of the screen.
 */
export function ToolExplorer() {
  const overview = useToolsOverview()
  const call = useToolCall()
  const [node, setNode] = useState('')
  const [toolName, setToolName] = useState('')
  const [values, setValues] = useState<Record<string, string>>({})
  const [caseId, setCaseId] = useState('')

  const nodes = overview.data?.nodes ?? []
  const tools = overview.data?.tools ?? []
  const selectedNode = nodes.find((n) => n.name === node) ?? nodes[0]
  const tool = tools.find((t) => t.name === toolName) ?? tools[0]
  const allowed = selectedNode && tool ? selectedNode.allowed.includes(tool.name) : false

  const fields = useMemo(() => schemaFields(tool?.inputSchema ?? null), [tool])

  function selectTool(name: string) {
    setToolName(name)
    setValues({})
    call.reset()
  }

  function run() {
    if (!selectedNode || !tool) return
    call.mutate({
      node: selectedNode.name,
      tool: tool.name,
      arguments: toArguments(fields, values),
      caseId: caseId.trim() || undefined,
    })
  }

  if (overview.isLoading) {
    return <p className="muted">Asking the client systems for their tools…</p>
  }
  if (overview.isError) {
    return <div className="banner banner--error">Could not load tools: {overview.error.detail}</div>
  }
  if (tools.length === 0) {
    return (
      <div className="banner">
        No client-system tools are configured. Set <code>ROOTSTOCK_TOOLS_FILE</code> for the backend to a
        domain's tools file.
      </div>
    )
  }

  const result = call.data?.result

  return (
    <div className="explorer">
      <div className="explorer__controls">
        <label>
          Node
          <select value={selectedNode?.name ?? ''} onChange={(e) => setNode(e.target.value)}>
            {nodes.map((n) => (
              <option key={n.name} value={n.name}>
                {n.name} ({n.allowed.length} tools)
              </option>
            ))}
          </select>
        </label>
        <label>
          Tool
          <select value={tool?.name ?? ''} onChange={(e) => selectTool(e.target.value)}>
            {tools.map((t) => (
              <option key={t.name} value={t.name}>
                {t.name}
                {selectedNode && !selectedNode.allowed.includes(t.name) ? ' (not allowed)' : ''}
              </option>
            ))}
          </select>
        </label>
        <label>
          Case id <span className="muted small">optional</span>
          <input value={caseId} placeholder="e.g. case-42" onChange={(e) => setCaseId(e.target.value)} />
        </label>
      </div>

      {tool && <ToolSummary tool={tool} allowed={allowed} node={selectedNode?.name ?? ''} />}

      {fields.length > 0 && (
        <div className="explorer__form">
          {fields.map((f) => (
            <label key={f.name} title={f.description}>
              <span>
                {f.name}
                {f.required && <span className="explorer__required"> *</span>}
                <span className="muted small"> {f.type}</span>
              </span>
              {f.options ? (
                <select value={values[f.name] ?? ''} onChange={(e) => setValues({ ...values, [f.name]: e.target.value })}>
                  <option value="">—</option>
                  {f.options.map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  type={f.type === 'integer' || f.type === 'number' ? 'number' : 'text'}
                  value={values[f.name] ?? ''}
                  placeholder={f.description}
                  onChange={(e) => setValues({ ...values, [f.name]: e.target.value })}
                />
              )}
            </label>
          ))}
        </div>
      )}

      <div className="actions">
        <button className="btn btn--primary" disabled={call.isPending || !tool} onClick={run}>
          {call.isPending ? 'Running…' : allowed ? 'Run' : 'Run (expect refusal)'}
        </button>
      </div>

      {call.isError && <div className="banner banner--error">{call.error.title}: {call.error.detail}</div>}

      {result && (
        <div className="answer explorer__result">
          <div className="answer__meta">
            <span className={STATUS_BADGE[result.status]}>{result.status}</span>
            <span className="muted small">
              {result.tool} in node <strong>{result.node}</strong>
              {result.connection ? ` · ${result.connection}` : ''} · {result.durationMillis} ms
            </span>
            {call.data?.traceUrl ? (
              <a className="small" href={call.data.traceUrl} target="_blank" rel="noreferrer">
                Langfuse trace ↗
              </a>
            ) : (
              call.data?.traceId && <span className="muted small mono">trace {call.data.traceId}</span>
            )}
          </div>
          {result.message && <p className="explorer__message">{result.message}</p>}
          {result.output && <pre className="explorer__output">{pretty(result.output)}</pre>}
        </div>
      )}
    </div>
  )
}

function ToolSummary({ tool, allowed, node }: { tool: ExplorerTool; allowed: boolean; node: string }) {
  return (
    <div className="explorer__tool">
      <div className="answer__meta">
        <span className={allowed ? 'badge badge--ok' : 'badge badge--busy'}>
          {allowed ? `allowed in ${node}` : `not allowed in ${node}: will be refused`}
        </span>
        <span className="badge badge--muted">{tool.access}</span>
        <span className="muted small">{tool.connection}</span>
      </div>
      {tool.available ? (
        <p className="muted small">{tool.description}</p>
      ) : (
        <p className="small">Unavailable: {tool.problem}</p>
      )}
    </div>
  )
}

interface Field {
  name: string
  type: string
  description?: string
  required: boolean
  options?: string[]
}

function schemaFields(schema: InputSchema | null): Field[] {
  const properties = schema?.properties ?? {}
  const required = new Set(schema?.required ?? [])
  return Object.entries(properties)
    .map(([name, p]) => ({
      name,
      type: p.enum ? 'enum' : (p.type ?? 'string'),
      description: p.description,
      required: required.has(name),
      options: p.enum,
    }))
    .sort((a, b) => Number(b.required) - Number(a.required))
}

/** Only filled-in fields are sent; numbers as numbers, so the tool sees the types it declared. */
function toArguments(fields: Field[], values: Record<string, string>): Record<string, unknown> {
  const args: Record<string, unknown> = {}
  for (const f of fields) {
    const raw = values[f.name]?.trim()
    if (!raw) continue
    args[f.name] = f.type === 'integer' || f.type === 'number' ? Number(raw) : raw
  }
  return args
}

function pretty(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

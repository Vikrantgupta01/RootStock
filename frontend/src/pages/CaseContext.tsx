import type { CaseRun } from '../api/cases'
import { Value } from './CaseRecord'
import { label, useDefinitions, type Json } from './caseValues'

/** One lookup a tool-calling agent made, as the run's result holds it. */
interface Lookup {
  source: 'plan' | 'model'
  tool: string
  arguments: Record<string, Json>
  /** OK, BLOCKED, UNKNOWN_TOOL, TOOL_ERROR, UNAVAILABLE, REFUSED or SKIPPED */
  status: string
  result: Json
  message: string | null
}

interface Gathered {
  channel: string
  summary: string
  modelCalls: number
  lookups: Lookup[]
}

/** Every channel a tool-calling agent wrote: an object with a lookups list. */
function gathered(run: CaseRun): Gathered[] {
  return Object.entries(run.result ?? {}).flatMap(([channel, value]) => {
    const v = value as Partial<Gathered> | null
    return v && typeof v === 'object' && Array.isArray(v.lookups)
      ? [{ channel, summary: v.summary ?? '', modelCalls: v.modelCalls ?? 0, lookups: v.lookups }]
      : []
  })
}

const STATUS_BADGE: Record<string, string> = {
  OK: 'badge--ok',
  SKIPPED: 'badge--muted',
  REFUSED: 'badge--busy',
  BLOCKED: 'badge--busy',
}

/**
 * What enrich looked up in the client system: the agent's summary, then each
 * lookup with who asked for it (the pack's plan or the model), its arguments,
 * and the client system's answer. Refused and failed calls are shown too.
 */
export function CaseContext({ run }: { run: CaseRun }) {
  const definitions = useDefinitions(run.pack)
  const panels = gathered(run)
  if (panels.length === 0) return null
  return (
    <>
      {panels.map((g) => (
        <section key={g.channel} className="context">
          <h3>Context from the client system</h3>
          {g.summary && <p className="context__summary">{g.summary}</p>}
          <p className="muted context__meta">
            {g.lookups.length} lookup{g.lookups.length === 1 ? '' : 's'} ·{' '}
            {g.lookups.filter((l) => l.source === 'plan').length} from the pack's plan,{' '}
            {g.lookups.filter((l) => l.source === 'model').length} asked for by the model · {g.modelCalls} model call
            {g.modelCalls === 1 ? '' : 's'}
          </p>
          <div className="context__lookups">
            {g.lookups.map((l, i) => (
              <details key={i} className="context__lookup" open={l.status === 'OK' && l.source === 'model'}>
                <summary>
                  <code>{l.tool}</code>
                  <span className="muted">
                    {Object.entries(l.arguments).map(([k, v]) => `${label(k).toLowerCase()} ${String(v)}`).join(', ')}
                  </span>
                  <span className={`badge ${STATUS_BADGE[l.status] ?? 'badge--error'}`}>{l.status.toLowerCase()}</span>
                  <span className="muted">{l.source === 'plan' ? 'plan' : 'model'}</span>
                </summary>
                {l.status === 'OK' ? (
                  <Value value={l.result} path="" missing={new Set()} definitions={definitions} absent="—" />
                ) : (
                  <p className="muted">{l.message}</p>
                )}
              </details>
            ))}
          </div>
        </section>
      ))}
    </>
  )
}

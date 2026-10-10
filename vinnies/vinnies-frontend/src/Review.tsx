import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type ApiError, type Decision, type Issue, type Json } from './api'
import { codeLabel, fieldLabel } from './format'
import { RecordForm } from './RecordForm'

// The Vinnies ontology's view of a case record that extraction fills in.
const PROJECTION = 'case-extraction'

const WHO: Record<Issue['answerableBy'], string> = {
  SUBMITTER: 'the member can answer',
  EXTERNAL: 'the household must answer',
  REVIEWER: 'for you to decide',
}

/**
 * One case waiting for a coordinator: what was found, the notes, the record,
 * what was looked up, and the draft. Approve it, correct the record (it goes
 * back through Rootstock's checks and comes back for review), or reject it.
 */
export function Review({ caseId, onDone, onBack }: { caseId: string; onDone: (message: string) => void; onBack: () => void }) {
  const detail = useQuery({ queryKey: ['case', caseId], queryFn: () => api.case(caseId) })
  const pack = detail.data?.pack
  const projection = useQuery({
    queryKey: ['projection', pack],
    queryFn: () => api.projection(pack!, PROJECTION),
    enabled: !!pack,
    staleTime: Infinity,
  })
  const [edited, setEdited] = useState<Record<string, Json> | null>(null)
  const [comment, setComment] = useState('')
  const decide = useMutation({
    mutationFn: (decision: Decision) => api.decide(caseId, decision, comment, decision === 'EDITED' ? edited! : undefined),
    onSuccess: (_, decision) =>
      onDone(
        decision === 'APPROVED' ? 'Approved. Rootstock carries on with the case.'
          : decision === 'EDITED' ? 'Changes sent back through the checks; the case returns for review once drafted again.'
            : 'Rejected. The case is closed.',
      ),
  })

  if (detail.isPending) return <p className="muted">Loading…</p>
  if (detail.isError) return <div className="error">{(detail.error as unknown as ApiError).detail}</div>
  const run = detail.data
  const result = run.result ?? {}
  const issues = result.issues ?? []
  const actions = result.actions ?? []
  const record = result.record ?? {}
  const waiting = run.waitingFor !== null
  const flagged = new Map(issues.filter((i) => i.path).map((i) => [i.path!, i.message]))

  return (
    <section className="review">
      <button className="link" onClick={onBack}>← All cases</button>
      <h1>Case from {new Date(run.startedAt).toLocaleDateString()}</h1>
      {!waiting && <div className="notice">This case is no longer waiting for review ({run.status.toLowerCase()}).</div>}

      <h2>What the checks found</h2>
      {issues.length === 0 ? <p className="muted">Nothing: every check passed.</p> : (
        <ul className="issues">
          {[...issues].sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'BLOCKING' ? -1 : 1)).map((i, n) => (
            <li key={n} className={i.severity === 'BLOCKING' ? 'blocking' : 'warning'}>
              <span className="pill">{i.severity === 'BLOCKING' ? 'Blocking' : 'Warning'}</span>
              <span>{i.message}</span>
              <span className="muted"> · {WHO[i.answerableBy]}</span>
            </li>
          ))}
        </ul>
      )}

      <div className="columns">
        <div>
          <h2>Visit notes</h2>
          <pre className="notes">{run.input}</pre>
          {result.context?.summary && (
            <>
              <h2>From the Vinnies system</h2>
              <p className="summary">{result.context.summary}</p>
            </>
          )}
        </div>
        <div>
          <h2>
            Case record
            {waiting && edited === null && (
              <button className="link" onClick={() => setEdited(structuredClone(record))}>Correct it</button>
            )}
            {edited !== null && <button className="link" onClick={() => setEdited(null)}>Discard changes</button>}
          </h2>
          {projection.data && edited !== null ? (
            <RecordForm schema={projection.data.schema} value={edited} onChange={setEdited} flagged={flagged} />
          ) : (
            <RecordView value={record} flagged={flagged} />
          )}
        </div>
      </div>

      <h2>Drafted for you</h2>
      {actions.length === 0 ? <p className="muted">Nothing drafted.</p> : (
        <ul className="actions">
          {actions.map((a, n) => (
            <li key={n}>
              <strong>{codeLabel(a.type)}</strong> {a.summary}
              <dl>
                {Object.entries(a.details).map(([k, v]) => (
                  <div key={k}>
                    <dt>{fieldLabel(k)}</dt>
                    <dd>{v === null ? '—' : String(v)}</dd>
                  </div>
                ))}
              </dl>
            </li>
          ))}
        </ul>
      )}

      {waiting && (
        <div className="decision">
          <label>
            Comment (kept with your decision)
            <textarea rows={2} value={comment} onChange={(e) => setComment(e.target.value)} />
          </label>
          {decide.isError && <div className="error">{(decide.error as unknown as ApiError).detail}</div>}
          <div className="buttons">
            {edited === null ? (
              <button className="primary" disabled={decide.isPending} onClick={() => decide.mutate('APPROVED')}>
                Approve
              </button>
            ) : (
              <button className="primary" disabled={decide.isPending} onClick={() => decide.mutate('EDITED')}>
                Send corrections back through the checks
              </button>
            )}
            <button className="danger" disabled={decide.isPending} onClick={() => decide.mutate('REJECTED')}>
              Reject
            </button>
          </div>
        </div>
      )}
    </section>
  )
}

function RecordView({ value, flagged, path = '' }: { value: Json; flagged: Map<string, string>; path?: string }) {
  if (value === null) return <span className="muted">not given</span>
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="muted">none</span>
    if (value.every((v) => typeof v !== 'object' || v === null)) return <span>{value.map((v) => codeLabel(String(v))).join(', ')}</span>
    return (
      <ol className="items">
        {value.map((v, i) => <li key={i}><RecordView value={v} flagged={flagged} path={`${path}[${i}]`} /></li>)}
      </ol>
    )
  }
  if (typeof value === 'object') {
    return (
      <dl className="record">
        {Object.entries(value).map(([k, v]) => {
          const at = path ? `${path}.${k}` : k
          return (
            <div key={k} className={flagged.has(at) ? 'flagged' : undefined} title={flagged.get(at)}>
              <dt>{fieldLabel(k)}</dt>
              <dd><RecordView value={v} flagged={flagged} path={at} /></dd>
            </div>
          )
        })}
      </dl>
    )
  }
  if (typeof value === 'boolean') return <span>{value ? 'Yes' : 'No'}</span>
  return <span>{typeof value === 'string' && /^[A-Z_]+$/.test(value) ? codeLabel(value) : String(value)}</span>
}

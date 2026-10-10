import { useState } from 'react'
import type { CaseGraph, CaseRun, RunEvent, Simulate } from '../api/cases'
import { AppShell } from '../components/AppShell'
import { useCaseDetail, useCaseGraph, useRecentCases, useRunEvents, useSubmitCase } from '../hooks/useCases'
import { CaseContext } from './CaseContext'
import { CaseIssues } from './CaseIssues'
import { NotesAndRecord } from './CaseRecord'
import './KnowledgePage.css'
import './CasesPage.css'

// Fictional, like all demo data.
// Linh Tran in Blacktown is a household in the Vinnies demo data, so enrich finds them.
const SAMPLE_NOTES = `Visited Linh Tran at home in Blacktown today. Partner and 2 kids. Got a final notice from the power company, power off Friday if not paid ($412).
Fridge nearly empty, kids went hungry Sunday. Gave $80 Coles voucher. Behind on rent 2 wks but landlord ok for now.
Happy for us to share details with the financial counsellor. Follow up next week.`

type NodeState = 'idle' | 'running' | 'done' | 'failed' | 'waiting'

/** Each node's state from the run's events; the last word on a node wins. */
function nodeStates(events: RunEvent[]): Record<string, NodeState> {
  const states: Record<string, NodeState> = {}
  for (const e of events) {
    if (!e.node) continue
    if (e.type === 'NODE_STARTED') states[e.node] = 'running'
    else if (e.type === 'NODE_FINISHED') states[e.node] = 'done'
    else if (e.type === 'NODE_FAILED') states[e.node] = 'failed'
    else if (e.type === 'RUN_PAUSED' && states[e.node] !== 'done') states[e.node] = 'waiting'
  }
  return states
}

export function CasesPage() {
  const graph = useCaseGraph()
  const recent = useRecentCases()
  const submit = useSubmitCase()
  const [notes, setNotes] = useState(SAMPLE_NOTES)
  const [simulate, setSimulate] = useState<Simulate>('none')
  const [selected, setSelected] = useState<string | null>(null)
  const detail = useCaseDetail(selected)
  const { events, error: streamError } = useRunEvents(selected)

  const start = () =>
    submit.mutate({ input: notes, simulate }, { onSuccess: (run) => setSelected(run.caseId) })

  return (
    <AppShell wide>
      <div className="page__header">
        <div>
          <span className="kicker">Graph · extraction</span>
          <h1>Cases</h1>
        </div>
      </div>
      <p className="page__lead">
        Submit visit notes and watch the pack's graph run them, node by node. Extract turns the notes into a case
        record in the ontology's own categories; enrich looks the household, its history, the guidelines and local
        services up in the client system; validate checks the record in four layers (its shape, the ontology's
        constraints, the pack's rules with the client's own limits, and a judge comparing it with the notes). A
        blocking issue goes back to the member or the household; warnings go to the coordinator. The later nodes
        are still stubs. A run stops where a person is
        needed: before review, or after clarify asks the member a question.
      </p>

      {graph.isError && <div className="banner banner--error">{graph.error.detail}</div>}

      <div className="cases__layout">
        <section className="cases__submit">
          <label>
            Visit notes
            <textarea rows={9} value={notes} onChange={(e) => setNotes(e.target.value)} />
          </label>
          <label>
            Also simulate (until the pack's rules exist)
            <select value={simulate} onChange={(e) => setSimulate(e.target.value as Simulate)}>
              <option value="none">Nothing extra</option>
              <option value="clarify">A detail the member can give: clarify</option>
              <option value="chase">A detail only the household can give: chase</option>
            </select>
          </label>
          <button className="btn" onClick={start} disabled={submit.isPending || !notes.trim() || !graph.data}>
            {submit.isPending ? 'Submitting…' : 'Submit case'}
          </button>
          {submit.isError && <div className="banner banner--error">{submit.error.detail}</div>}

          <h3>Your recent cases</h3>
          {recent.data?.length === 0 && <p className="muted">None yet.</p>}
          <ul className="cases__recent">
            {recent.data?.map((run) => (
              <li key={run.caseId}>
                <button className={`cases__recent-item${selected === run.caseId ? ' is-on' : ''}`} onClick={() => setSelected(run.caseId)}>
                  <StatusBadge run={run} />
                  <span className="muted">{new Date(run.startedAt).toLocaleTimeString()}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>

        <section>
          {graph.data && <GraphView graph={graph.data} states={nodeStates(events)} />}
        </section>
      </div>

      {selected && (
        <section className="cases__run">
          <div className="cases__run-head">
            <h2>Run</h2>
            {detail.data && <StatusBadge run={detail.data} />}
            {detail.data?.traceUrl && (
              <a href={detail.data.traceUrl} target="_blank" rel="noreferrer">
                Langfuse trace ↗
              </a>
            )}
            <span className="muted cases__id">case {selected}</span>
          </div>
          {streamError && <div className="banner banner--error">{streamError}</div>}
          {detail.data && detail.data.status !== 'RUNNING' && (
            <>
              <CaseIssues run={detail.data} />
              <NotesAndRecord run={detail.data} />
              <CaseContext run={detail.data} />
            </>
          )}
          <EventLog events={events} />
          {detail.data?.result && detail.data.status !== 'RUNNING' && <Result run={detail.data} />}
        </section>
      )}
    </AppShell>
  )
}

function StatusBadge({ run }: { run: CaseRun }) {
  const cls = {
    RUNNING: 'badge--busy',
    PAUSED: 'badge--muted',
    COMPLETED: 'badge--ok',
    FAILED: 'badge--error',
    PARKED: 'badge--busy',
  }[run.status]
  const text =
    run.status !== 'RUNNING' && run.waitingFor
      ? `waiting for ${run.waitingFor.join(' or ')}`
      : run.status === 'PAUSED' && run.pause
        ? `paused ${run.pause.before ? 'before' : 'after'} ${run.pause.node}`
        : run.status === 'COMPLETED' && run.caseStatus && run.caseStatus !== 'COMPLETED'
          ? run.caseStatus.toLowerCase().replace(/_/g, ' ')
          : run.status.toLowerCase()
  return <span className={`badge ${cls}`}>{text}</span>
}

function GraphView({ graph, states }: { graph: CaseGraph; states: Record<string, NodeState> }) {
  const branches = (from: string) => graph.edges.find((e) => e.from === from)?.branches ?? []
  return (
    <div className="cases__graph">
      <p className="muted">
        {graph.name} {graph.version} · pack {graph.pack}
      </p>
      <div className="cases__start">START → {branches('START').map((b) => b.to).join(', ')}</div>
      {graph.nodes.map((node) => (
        <div key={node.id} className={`cases__node cases__node--${states[node.id] ?? 'idle'}`}>
          <div className="cases__node-head">
            <strong>{node.id}</strong>
            <span className="muted">{node.agent ? `${node.agent} · ${node.kind}` : node.kind}</span>
            {graph.interruptBefore.includes(node.id) && <span className="badge">pauses before</span>}
            {graph.interruptAfter.includes(node.id) && <span className="badge">pauses after</span>}
          </div>
          <ul className="cases__branches">
            {branches(node.id).map((b) => (
              <li key={b.to + (b.label ?? '')}>
                → <strong>{b.to}</strong>
                {b.label && <span className="muted"> if {b.label}</span>}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

function EventLog({ events }: { events: RunEvent[] }) {
  if (events.length === 0) return <p className="muted">Waiting for the run…</p>
  const t0 = new Date(events[0].at).getTime()
  return (
    <table className="table cases__events">
      <thead>
        <tr><th>+ms</th><th>Event</th><th>Node</th><th>Took</th><th>Detail</th></tr>
      </thead>
      <tbody>
        {events.map((e) => (
          <tr key={e.seq}>
            <td className="muted">{new Date(e.at).getTime() - t0}</td>
            <td><code>{e.type}</code></td>
            <td>{e.node}</td>
            <td className="muted">{e.durationMs !== null ? `${e.durationMs} ms` : ''}</td>
            <td>{e.detail}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Result({ run }: { run: CaseRun }) {
  const result = run.result ?? {}
  const list = (key: string) => (Array.isArray(result[key]) ? (result[key] as Record<string, unknown>[]) : [])
  return (
    <div className="cases__result">
      {run.error && (
        <div className={`banner ${run.status === 'PARKED' ? 'banner--busy' : 'banner--error'}`}>
          {run.status === 'PARKED' ? 'Parked for a person: ' : ''}
          {run.error}
        </div>
      )}
      <section>
        <h3>Proposed actions</h3>
        {list('actions').length === 0 ? <p className="muted">None.</p> : (
          <ul>{list('actions').map((a, n) => <li key={n}><code>{String(a.type)}</code> {String(a.summary)}</li>)}</ul>
        )}
      </section>
      <section>
        <h3>Audit</h3>
        <ul>{list('audit').map((a, n) => <li key={n}><strong>{String(a.node)}</strong>: {String(a.note)}</li>)}</ul>
      </section>
    </div>
  )
}

import { useQuery } from '@tanstack/react-query'
import { api, type ApiError, type CaseRun } from './api'
import { codeLabel } from './format'

/** The cases waiting for this coordinator, oldest first. */
export function Queue({ onOpen }: { onOpen: (caseId: string) => void }) {
  const awaiting = useQuery({ queryKey: ['awaiting'], queryFn: api.awaiting, refetchInterval: 15_000 })

  if (awaiting.isPending) return <p className="muted">Loading…</p>
  if (awaiting.isError) return <div className="error">{(awaiting.error as unknown as ApiError).detail}</div>
  const cases = awaiting.data
  return (
    <section>
      <h1>
        {cases.length === 0 ? 'No cases waiting for review' : `${cases.length} case${cases.length === 1 ? '' : 's'} waiting for review`}
      </h1>
      {cases.length > 0 && <p className="muted">Oldest first. Each one was drafted by Rootstock and needs a coordinator's decision.</p>}
      <ul className="queue">
        {cases.map((c) => (
          <QueueRow key={c.caseId} run={c} onOpen={onOpen} />
        ))}
      </ul>
    </section>
  )
}

function QueueRow({ run, onOpen }: { run: CaseRun; onOpen: (caseId: string) => void }) {
  // The list has no record; each row reads its case for the summary line.
  const detail = useQuery({ queryKey: ['case', run.caseId], queryFn: () => api.case(run.caseId) })
  const record = detail.data?.result?.record
  const issues = detail.data?.result?.issues ?? []
  const household = record?.household as Record<string, unknown> | null | undefined
  const needs = Array.isArray(record?.needs) ? (record.needs as Record<string, unknown>[]) : []
  return (
    <li>
      <button className="row" onClick={() => onOpen(run.caseId)}>
        <span className={`pill urgency-${String(record?.urgency ?? 'none').toLowerCase()}`}>
          {record?.urgency ? codeLabel(String(record.urgency)) : '…'}
        </span>
        <span className="row-main">
          <strong>{household?.suburb ? String(household.suburb) : 'Suburb not given'}</strong>
          <span className="muted"> · {needs.map((n) => codeLabel(String(n.category))).join(', ') || 'needs…'}</span>
        </span>
        <span className="muted">
          {issues.length > 0 && `${issues.length} issue${issues.length === 1 ? '' : 's'} · `}
          submitted {new Date(run.startedAt).toLocaleString()}
        </span>
      </button>
    </li>
  )
}

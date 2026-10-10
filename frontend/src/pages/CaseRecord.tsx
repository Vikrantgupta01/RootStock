import type { CaseRun } from '../api/cases'
import { caseIssues, label, pathLabel, REQUIRED_FIELD, useDefinitions, type Json } from './caseValues'

/**
 * The notes as submitted beside the record extracted from them. Codes are the
 * ontology's own (hover for their definition). A required field the notes did
 * not give is marked, because validate turns it into a question for the member;
 * a field another check flagged is marked too, with the issue on hover.
 */
export function NotesAndRecord({ run }: { run: CaseRun }) {
  const definitions = useDefinitions(run.pack)
  const issues = caseIssues(run.result)
  const missing = new Set(issues.filter((i) => i.ruleId === REQUIRED_FIELD && i.path).map((i) => i.path!))
  const flagged = new Map<string, string>()
  for (const i of issues) {
    if (i.path && i.ruleId !== REQUIRED_FIELD) {
      flagged.set(i.path, flagged.has(i.path) ? `${flagged.get(i.path)}\n${i.message}` : i.message)
    }
  }
  const record = (run.result?.record ?? null) as Json

  return (
    <div className="record">
      <section>
        <h3>Notes as submitted</h3>
        <pre className="record__notes">{run.input}</pre>
      </section>
      <section>
        <h3>Extracted record</h3>
        {record === null || typeof record !== 'object' ? (
          <p className="muted">{run.status === 'PARKED' ? 'Not extracted: the case was parked.' : 'No record yet.'}</p>
        ) : (
          <>
            {missing.size > 0 && (
              <p className="record__missing-note">
                Not in the notes, so the member will be asked: {[...missing].map(pathLabel).join(', ')}
              </p>
            )}
            <Value value={record} path="" missing={missing} flagged={flagged} definitions={definitions} />
            <details className="record__raw">
              <summary>JSON</summary>
              <pre className="cases__pre">{JSON.stringify(record, null, 2)}</pre>
            </details>
          </>
        )}
      </section>
    </div>
  )
}

const NONE = new Map<string, string>()

interface ValueProps {
  value: Json
  path: string
  missing: Set<string>
  /** Field path → what another check said about it. */
  flagged?: Map<string, string>
  definitions: Map<string, string>
  /** What an empty value says; "not in the notes" for an extracted record. */
  absent?: string
}

/** Any JSON value as a readable field list; vocabulary codes explained on hover. */
export function Value({ value, path, missing, flagged = NONE, definitions, absent = 'not in the notes' }: ValueProps) {
  if (value === null) {
    return <span className={missing.has(path) ? 'record__gap record__gap--asked' : 'record__gap'}>{absent}</span>
  }
  if (typeof value === 'boolean') return <span>{value ? 'yes' : 'no'}</span>
  if (typeof value === 'number') return <span>{value}</span>
  if (typeof value === 'string') {
    const definition = definitions.get(value)
    return definition ? <abbr className="record__code" title={definition}>{value}</abbr> : <span>{value}</span>
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return <span className={missing.has(path) ? 'record__gap record__gap--asked' : 'record__gap'}>none</span>
    }
    if (value.every((v) => typeof v !== 'object' || v === null)) {
      return (
        <span className="record__codes">
          {value.map((v, i) => <Value key={i} value={v} path={`${path}[${i}]`} missing={missing} flagged={flagged} definitions={definitions} absent={absent} />)}
        </span>
      )
    }
    return (
      <ol className="record__items">
        {value.map((v, i) => (
          <li key={i}>
            <Value value={v} path={`${path}[${i}]`} missing={missing} flagged={flagged} definitions={definitions} absent={absent} />
          </li>
        ))}
      </ol>
    )
  }
  return (
    <dl className="record__fields">
      {Object.entries(value).map(([key, v]) => {
        const at = path ? `${path}.${key}` : key
        return (
          <div
            key={key}
            className={`record__field${missing.has(at) ? ' is-missing' : flagged.has(at) ? ' is-flagged' : ''}`}
            title={flagged.get(at)}
          >
            <dt>{label(key)}</dt>
            <dd>
              <Value value={v} path={at} missing={missing} flagged={flagged} definitions={definitions} absent={absent} />
            </dd>
          </div>
        )
      })}
    </dl>
  )
}

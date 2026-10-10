import type { CaseRun } from '../api/cases'
import { usePack } from '../hooks/useOntology'

type Json = null | boolean | number | string | Json[] | { [key: string]: Json }

/** visitDate → Visit date */
function label(key: string) {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** household.suburb → Household › suburb */
function pathLabel(path: string) {
  return path.split('.').map((part, i) => (i === 0 ? label(part) : label(part).toLowerCase())).join(' › ')
}

/**
 * The notes as submitted beside the record extracted from them. Codes are the
 * ontology's own (hover for their definition); a required field the notes did
 * not give is marked, because validate turns it into a question for the member.
 */
export function NotesAndRecord({ run }: { run: CaseRun }) {
  const pack = usePack(run.pack)
  const definitions = new Map<string, string>()
  for (const v of pack.data?.ontology?.vocabularies ?? []) {
    for (const t of v.terms) {
      if (t.definition && !definitions.has(t.code)) definitions.set(t.code, `${v.name}: ${t.definition}`)
    }
  }
  const issues = Array.isArray(run.result?.issues) ? (run.result.issues as Record<string, unknown>[]) : []
  const missing = new Set(issues.map((i) => i.path).filter((p): p is string => typeof p === 'string'))
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
            <Value value={record} path="" missing={missing} definitions={definitions} />
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

interface ValueProps {
  value: Json
  path: string
  missing: Set<string>
  definitions: Map<string, string>
}

function Value({ value, path, missing, definitions }: ValueProps) {
  if (value === null) {
    return <span className={missing.has(path) ? 'record__gap record__gap--asked' : 'record__gap'}>not in the notes</span>
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
          {value.map((v, i) => <Value key={i} value={v} path={`${path}[${i}]`} missing={missing} definitions={definitions} />)}
        </span>
      )
    }
    return (
      <ol className="record__items">
        {value.map((v, i) => (
          <li key={i}>
            <Value value={v} path={`${path}[${i}]`} missing={missing} definitions={definitions} />
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
          <div key={key} className={missing.has(at) ? 'record__field is-missing' : 'record__field'}>
            <dt>{label(key)}</dt>
            <dd>
              <Value value={v} path={at} missing={missing} definitions={definitions} />
            </dd>
          </div>
        )
      })}
    </dl>
  )
}

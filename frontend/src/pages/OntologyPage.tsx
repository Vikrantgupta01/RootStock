import { useState } from 'react'
import type { EntityView, OntologyView, PackSummary } from '../api/ontology'
import { AppShell } from '../components/AppShell'
import {
  useCoreOntology,
  useOntologyOverview,
  usePack,
  useProjection,
  useReloadPacks,
} from '../hooks/useOntology'
import { hasRole, useCurrentUser } from '../hooks/useSession'
import './KnowledgePage.css'
import './OntologyPage.css'

const CORE = 'rootstock-core'
const SECTIONS = ['Concepts', 'Vocabularies', 'Constraints', 'Projections'] as const
type Section = (typeof SECTIONS)[number]

export function OntologyPage() {
  const user = useCurrentUser()
  const overview = useOntologyOverview()
  const reload = useReloadPacks()
  const [selected, setSelected] = useState<string | null>(null)

  const packs = overview.data?.packs ?? []
  // Default to the first pack; the core is always there to fall back on.
  const current = selected ?? packs[0]?.name ?? CORE

  return (
    <AppShell wide>
      <div className="page__header">
        <div>
          <span className="kicker">Domain packs · ontology</span>
          <h1>Ontology explorer</h1>
        </div>
        {hasRole(user.data, 'ADMIN') && (
          <button className="btn btn--sm" onClick={() => reload.mutate()} disabled={reload.isPending}>
            {reload.isPending ? 'Reloading…' : 'Reload packs'}
          </button>
        )}
      </div>
      <p className="page__lead">
        Each domain pack defines its concepts in <code>ontology.yaml</code>, extending Rootstock's core ontology.
        Extraction schemas and prompt glossaries are generated from it, so a concept is defined once. A broken
        ontology is listed with its errors instead of stopping Rootstock.
      </p>

      {overview.isLoading && <p className="muted">Loading packs…</p>}
      {overview.isError && <div className="banner banner--error">Could not load the ontology: {overview.error.detail}</div>}
      {reload.isError && <div className="banner banner--error">Reload failed: {reload.error.detail}</div>}

      {overview.data && (
        <>
          {overview.data.pathProblems.map((p) => (
            <div key={p} className="banner banner--error">{p}</div>
          ))}
          {overview.data.paths.length === 0 && (
            <div className="banner">No domain packs configured. Set ROOTSTOCK_PACKS_PATHS to the folder holding them.</div>
          )}
          <nav className="tabs">
            {packs.map((p) => (
              <button key={p.name} className={`tab${current === p.name ? ' tab--on' : ''}`} onClick={() => setSelected(p.name)}>
                {p.name} <StatusBadge pack={p} />
              </button>
            ))}
            <button className={`tab${current === CORE ? ' tab--on' : ''}`} onClick={() => setSelected(CORE)}>
              {CORE} <span className="badge badge--muted">core</span>
            </button>
          </nav>
          <section className="tabpane">
            {current === CORE ? <CorePane /> : <PackPane name={current} />}
          </section>
          <p className="muted ontology__loaded">
            Loaded {new Date(overview.data.loadedAt).toLocaleString()} from {overview.data.paths.join(', ') || 'nowhere'}
          </p>
        </>
      )}
    </AppShell>
  )
}

function StatusBadge({ pack }: { pack: PackSummary }) {
  if (pack.status === 'VALID') return <span className="badge badge--ok">valid</span>
  if (pack.status === 'INVALID') return <span className="badge badge--error">invalid</span>
  return <span className="badge badge--muted">no ontology</span>
}

function CorePane() {
  const core = useCoreOntology(true)
  if (core.isLoading) return <p className="muted">Loading…</p>
  if (core.isError) return <div className="banner banner--error">{core.error.detail}</div>
  return core.data ? <OntologyBody ontology={core.data} pack={null} /> : null
}

function PackPane({ name }: { name: string }) {
  const pack = usePack(name)
  if (pack.isLoading) return <p className="muted">Loading…</p>
  if (pack.isError) return <div className="banner banner--error">{pack.error.detail}</div>
  if (!pack.data) return null
  const { pack: summary, ontology } = pack.data

  return (
    <>
      <p className="muted ontology__files">
        {summary.location} · {summary.files.join(', ')}
      </p>
      {summary.status === 'INVALID' && (
        <div className="banner banner--error ontology__problems">
          <strong>
            ontology.yaml has {summary.problems.length} problem{summary.problems.length === 1 ? '' : 's'}; the pack is
            not used until they are fixed.
          </strong>
          <ul>
            {summary.problems.map((p, i) => (
              <li key={i}>
                {p.at && <code>{p.at}</code>} {p.message}
              </li>
            ))}
          </ul>
        </div>
      )}
      {summary.status === 'NO_ONTOLOGY' && <div className="banner">This pack has no ontology.yaml.</div>}
      {ontology && <OntologyBody ontology={ontology} pack={summary.name} />}
    </>
  )
}

function OntologyBody({ ontology, pack }: { ontology: OntologyView; pack: string | null }) {
  const [section, setSection] = useState<Section>('Concepts')
  const sections = pack ? SECTIONS : SECTIONS.filter((s) => s !== 'Projections')

  return (
    <>
      <p className="ontology__summary">
        <strong>{ontology.name}</strong> {ontology.version}
        {ontology.extendsName && <> · extends {ontology.extendsName}</>}
        {ontology.description && <> · {ontology.description}</>}
      </p>
      <div className="ontology__sections">
        {sections.map((s) => (
          <button key={s} className={`btn btn--sm${section === s ? '' : ' btn--ghost'}`} onClick={() => setSection(s)}>
            {s}
          </button>
        ))}
      </div>
      {section === 'Concepts' && (
        <div className="ontology__grid">
          {ontology.entities.map((e) => (
            <EntityCard key={e.name} entity={e} />
          ))}
        </div>
      )}
      {section === 'Vocabularies' && <Vocabularies ontology={ontology} />}
      {section === 'Constraints' && <Constraints ontology={ontology} />}
      {section === 'Projections' && pack && <Projections ontology={ontology} pack={pack} />}
    </>
  )
}

function EntityCard({ entity }: { entity: EntityView }) {
  return (
    <section className="ontology__card">
      <div className="ontology__card-head">
        <h3>{entity.name}</h3>
        {entity.ancestors.length > 0 && <span className="badge">extends {entity.ancestors.join(' → ')}</span>}
      </div>
      {entity.description && <p className="muted">{entity.description}</p>}
      {entity.attributes.length + entity.relations.length > 0 && (
        <table className="table table--nested">
          <tbody>
            {entity.attributes.map((a) => (
              <tr key={a.name} title={a.description ?? undefined}>
                <td><code>{a.name}</code></td>
                <td>
                  {a.vocab ? <span className="ontology__vocab">{a.vocab}</span> : a.type}
                  {a.many && '[]'}
                  {(a.min !== null || a.max !== null) && (
                    <span className="muted"> {a.min ?? ''}…{a.max ?? ''}</span>
                  )}
                </td>
                <td className="ontology__flags">
                  {a.required && <span className="badge">required</span>}
                  {a.pii && <span className="badge badge--error">pii</span>}
                  {a.declaredBy !== entity.name && <span className="badge badge--muted">from {a.declaredBy}</span>}
                </td>
              </tr>
            ))}
            {entity.relations.map((r) => (
              <tr key={r.name} title={r.description ?? undefined}>
                <td><code>{r.name}</code></td>
                <td>
                  → <strong>{r.to}</strong>
                  {r.many && '[]'}
                  {r.min !== null && <span className="muted"> min {r.min}</span>}
                  {r.field !== r.name && <span className="muted"> as {r.field}</span>}
                </td>
                <td className="ontology__flags">
                  {r.required && <span className="badge">required</span>}
                  {r.declaredBy !== entity.name && <span className="badge badge--muted">from {r.declaredBy}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

function Vocabularies({ ontology }: { ontology: OntologyView }) {
  if (ontology.vocabularies.length === 0) return <p className="muted">No vocabularies.</p>
  return (
    <div className="ontology__stack">
      {ontology.vocabularies.map((v) => (
        <section key={v.name}>
          <h3 className="ontology__vocab">{v.name}</h3>
          {v.description && <p className="muted">{v.description}</p>}
          <table className="table">
            <thead>
              <tr><th>Code</th><th>Definition</th><th>Also said as</th></tr>
            </thead>
            <tbody>
              {v.terms.map((t) => (
                <tr key={t.code}>
                  <td><code>{t.code}</code></td>
                  <td>{t.definition}</td>
                  <td className="muted">{t.synonyms.join(', ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
    </div>
  )
}

function conditions(c: Record<string, unknown>) {
  return Object.entries(c)
    .map(([path, value]) => `${path} ${Array.isArray(value) ? `in ${value.join(', ')}` : `is ${String(value)}`}`)
    .join(' and ')
}

function Constraints({ ontology }: { ontology: OntologyView }) {
  if (ontology.constraints.length === 0) return <p className="muted">No constraints.</p>
  return (
    <div className="ontology__stack">
      {ontology.constraints.map((c) => (
        <section key={c.id} className="ontology__card">
          <h3><code>{c.id}</code></h3>
          {c.description && <p>{c.description}</p>}
          <p className="ontology__rule">
            <span className="muted">when</span> {conditions(c.when)} <span className="muted">then</span>{' '}
            {conditions(c.require)}
          </p>
        </section>
      ))}
    </div>
  )
}

function Projections({ ontology, pack }: { ontology: OntologyView; pack: string }) {
  const [chosen, setChosen] = useState<string | null>(null)
  const current = chosen ?? ontology.projections[0]?.name ?? null
  const output = useProjection(pack, current)
  const projection = ontology.projections.find((p) => p.name === current)

  if (ontology.projections.length === 0) return <p className="muted">No projections.</p>
  return (
    <>
      <div className="ontology__sections">
        {ontology.projections.map((p) => (
          <button key={p.name} className={`btn btn--sm${current === p.name ? '' : ' btn--ghost'}`} onClick={() => setChosen(p.name)}>
            {p.name}
          </button>
        ))}
      </div>
      {projection && (
        <p className="ontology__summary">
          {projection.description && <>{projection.description} </>}
          <span className="muted">
            Root <strong>{projection.root}</strong>
            {projection.embed.length > 0 && <> · embeds {projection.embed.join(', ')}</>}
            {projection.reference.length > 0 && <> · references {projection.reference.join(', ')} by id</>}
            {projection.include.length > 0 && <> · only {projection.include.join(', ')}</>}
          </span>
        </p>
      )}
      {output.isLoading && <p className="muted">Generating…</p>}
      {output.isError && <div className="banner banner--error">{output.error.detail}</div>}
      {output.data && (
        <div className="ontology__outputs">
          <section>
            <h3>JSON Schema</h3>
            <pre className="ontology__pre">{JSON.stringify(output.data.schema, null, 2)}</pre>
          </section>
          <section>
            <h3>Prompt glossary</h3>
            <pre className="ontology__pre">{output.data.glossary}</pre>
          </section>
        </div>
      )}
    </>
  )
}

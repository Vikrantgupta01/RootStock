import { useState } from 'react'
import { Link } from 'react-router-dom'
import { getTenant, setTenant } from '../api/tenant'
import { ActivityTab } from '../components/rag/ActivityTab'
import { DocumentsTab } from '../components/rag/DocumentsTab'
import { HealthBadge } from '../components/HealthBadge'
import { PlaygroundTab } from '../components/rag/PlaygroundTab'
import { TuningTab } from '../components/rag/TuningTab'
import { useTenant } from '../hooks/useTenant'
import './KnowledgePage.css'

const TABS = ['Documents', 'Tuning', 'Playground', 'Activity'] as const
type Tab = (typeof TABS)[number]

export function KnowledgePage() {
  const tenant = useTenant()
  const [tab, setTab] = useState<Tab>(() => {
    try {
      return (localStorage.getItem('rootstock.kb.tab') as Tab) || 'Documents'
    } catch {
      return 'Documents'
    }
  })
  const [tenantDraft, setTenantDraft] = useState(getTenant())

  function selectTab(t: Tab) {
    setTab(t)
    try {
      localStorage.setItem('rootstock.kb.tab', t)
    } catch {
      // ignore
    }
  }

  return (
    <main className="page page--wide">
      <header className="page__header">
        <h1>
          <Link to="/" className="brandlink">
            RootStock
          </Link>{' '}
          <span className="muted">/ Knowledge base</span>
        </h1>
        <HealthBadge />
      </header>

      <form
        className="tenant-bar"
        onSubmit={(e) => {
          e.preventDefault()
          setTenant(tenantDraft)
        }}
      >
        <label>
          Tenant
          <input value={tenantDraft} onChange={(e) => setTenantDraft(e.target.value)} placeholder="default" />
        </label>
        <button className="btn btn--sm" type="submit" disabled={tenantDraft.trim() === tenant}>
          switch
        </button>
        <span className="muted small">sent as the X-Tenant-Id header</span>
      </form>

      <nav className="tabs">
        {TABS.map((t) => (
          <button key={t} className={`tab${tab === t ? ' tab--on' : ''}`} onClick={() => selectTab(t)}>
            {t}
          </button>
        ))}
      </nav>

      <section className="tabpane">
        {tab === 'Documents' && <DocumentsTab />}
        {tab === 'Tuning' && <TuningTab />}
        {tab === 'Playground' && <PlaygroundTab />}
        {tab === 'Activity' && <ActivityTab />}
      </section>
    </main>
  )
}

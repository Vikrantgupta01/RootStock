import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AccessTab } from '../components/rag/AccessTab'
import { ActivityTab } from '../components/rag/ActivityTab'
import { DocumentsTab } from '../components/rag/DocumentsTab'
import { HealthBadge } from '../components/HealthBadge'
import { IdentityBar } from '../components/IdentityBar'
import { PlaygroundTab } from '../components/rag/PlaygroundTab'
import { TuningTab } from '../components/rag/TuningTab'
import { hasRole, useCurrentUser } from '../hooks/useSession'
import './KnowledgePage.css'

const ALL_TABS = ['Documents', 'Tuning', 'Access', 'Playground', 'Activity'] as const
type Tab = (typeof ALL_TABS)[number]

/** Tuning and Access change what everyone else sees, so both are admin-only. */
const ADMIN_ONLY: Tab[] = ['Tuning', 'Access']

export function KnowledgePage() {
  const user = useCurrentUser()
  const isAdmin = hasRole(user.data, 'ADMIN')
  const tabs = ALL_TABS.filter((t) => isAdmin || !ADMIN_ONLY.includes(t))

  const [tab, setTab] = useState<Tab>(() => {
    try {
      return (localStorage.getItem('rootstock.kb.tab') as Tab) || 'Documents'
    } catch {
      return 'Documents'
    }
  })
  // A remembered tab the current user isn't allowed to open falls back rather
  // than rendering a pane whose every request would 403.
  const current: Tab = tabs.includes(tab) ? tab : 'Documents'

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

      <IdentityBar />

      <nav className="tabs">
        {tabs.map((t) => (
          <button key={t} className={`tab${current === t ? ' tab--on' : ''}`} onClick={() => selectTab(t)}>
            {t}
          </button>
        ))}
      </nav>

      <section className="tabpane">
        {current === 'Documents' && <DocumentsTab />}
        {current === 'Tuning' && <TuningTab />}
        {current === 'Access' && <AccessTab />}
        {current === 'Playground' && <PlaygroundTab />}
        {current === 'Activity' && <ActivityTab />}
      </section>
    </main>
  )
}

import { AppShell } from '../components/AppShell'
import { usePlatformStatus } from '../hooks/useStatus'
import './KnowledgePage.css'
import './StatusPage.css'

export function StatusPage() {
  const status = usePlatformStatus()

  return (
    <AppShell>
      <div className="page__header">
        <div>
          <span className="kicker">Platform</span>
          <h1>Status</h1>
        </div>
        <button className="btn btn--sm" onClick={() => status.refetch()} disabled={status.isFetching}>
          {status.isFetching ? 'Checking…' : 'Refresh'}
        </button>
      </div>
      <p className="page__lead">
        Each service Rootstock depends on, checked just now with a real round trip: green means it
        answered. A service that does not answer within 15 seconds turns red instead of holding up the page.
      </p>

      {status.isLoading && <p className="muted">Checking Bedrock, RDS, Cognito, Langfuse and the client systems…</p>}
      {status.isError && <div className="banner banner--error">Could not get the status: {status.error.detail}</div>}

      {status.data && (
        <>
          <p className={status.data.allOk ? 'status__state--ok' : 'status__state--down'}>
            {status.data.allOk ? 'All services answering.' : 'Some services are not answering.'}
          </p>
          <div className="status__grid">
            {status.data.checks.map((check) => (
              <section key={check.name} className={`status__card status__card--${check.ok ? 'ok' : 'down'}`}>
                <div className="status__head">
                  <span className="status__name">{check.name}</span>
                  <span className={`status__state--${check.ok ? 'ok' : 'down'}`}>{check.ok ? '● up' : '● down'}</span>
                </div>
                <p className="status__detail">{check.detail}</p>
                <div className="status__meta muted">
                  <span>{check.latencyMs} ms</span>
                  {check.link && (
                    <a href={check.link} target="_blank" rel="noreferrer">
                      latest trace ↗
                    </a>
                  )}
                </div>
              </section>
            ))}
          </div>
        </>
      )}
    </AppShell>
  )
}

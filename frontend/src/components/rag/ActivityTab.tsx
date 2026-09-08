import { useState } from 'react'
import type { JobState } from '../../api/rag'
import { useJobs } from '../../hooks/rag'
import { formatDate, relativeTime } from '../../lib/format'
import { StatusBadge } from './StatusBadge'

const FILTERS: (JobState | 'ALL')[] = ['ALL', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED']

export function ActivityTab() {
  const [filter, setFilter] = useState<JobState | 'ALL'>('ALL')
  const jobs = useJobs(filter)

  return (
    <div className="stack">
      <div className="filters">
        {FILTERS.map((f) => (
          <button
            key={f}
            className={`chip${filter === f ? ' chip--on' : ''}`}
            onClick={() => setFilter(f)}
          >
            {f.toLowerCase()}
          </button>
        ))}
        <span className="muted small">auto-refreshing</span>
      </div>

      {jobs.isLoading && <p className="muted">Loading…</p>}
      {jobs.data && jobs.data.content.length === 0 && <p className="muted">No jobs.</p>}

      {jobs.data && jobs.data.content.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Kind</th>
              <th>State</th>
              <th>Attempts</th>
              <th>Created</th>
              <th>Updated</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {jobs.data.content.map((j) => (
              <tr key={j.id}>
                <td>{j.kind.toLowerCase()}</td>
                <td>
                  <StatusBadge status={j.state} />
                </td>
                <td>
                  {j.attempts}/{j.maxAttempts}
                </td>
                <td className="muted" title={formatDate(j.createdAt)}>
                  {relativeTime(j.createdAt)}
                </td>
                <td className="muted" title={formatDate(j.updatedAt)}>
                  {relativeTime(j.updatedAt)}
                </td>
                <td className="muted small">
                  {j.errorMessage ? <span className="error-text">{j.errorMessage}</span> : j.lockedBy ?? '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

type Tone = 'ok' | 'busy' | 'error' | 'muted'

const TONE: Record<string, Tone> = {
  INDEXED: 'ok',
  SUCCEEDED: 'ok',
  COMPLETED: 'ok',
  PENDING: 'busy',
  QUEUED: 'busy',
  PROCESSING: 'busy',
  RUNNING: 'busy',
  FAILED: 'error',
  SUPERSEDED: 'muted',
}

export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (!status) return <span className="badge badge--muted">—</span>
  const tone = TONE[status] ?? 'muted'
  return <span className={`badge badge--${tone}`}>{status.toLowerCase()}</span>
}

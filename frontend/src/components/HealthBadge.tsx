import { useHealth } from '../hooks/useHealth'

export function HealthBadge() {
  const { data, isLoading, isError } = useHealth()

  const state = isLoading ? 'loading' : isError ? 'down' : 'up'
  const label = isLoading
    ? 'checking API…'
    : isError
      ? 'API unreachable'
      : `API ${data?.status ?? 'UP'} · v${data?.version ?? 'dev'}`

  return (
    <span className={`health-badge health-badge--${state}`}>
      <span className="health-badge__dot" />
      {label}
    </span>
  )
}

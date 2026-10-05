import { Link } from 'react-router-dom'
import { AgentWindow } from '../components/AgentWindow'
import { AppShell } from '../components/AppShell'

export function AgentPage() {
  return (
    <AppShell>
      <div className="page__header">
        <div>
          <span className="kicker">ReAct · LangGraph4j</span>
          <h1>Agent</h1>
        </div>
      </div>
      <p className="page__lead">
        An agent that decides for itself when to search the{' '}
        <Link to="/knowledge">knowledge base</Link> or check the date, reads what comes back, and
        searches again if it needs to before answering. Each answer shows the steps it took.
      </p>
      <AgentWindow />
    </AppShell>
  )
}

import { AppShell } from '../components/AppShell'
import { ToolExplorer } from '../components/tools/ToolExplorer'
import { hasRole, useCurrentUser } from '../hooks/useSession'
import './KnowledgePage.css'
import './ToolsPage.css'

export function ToolsPage() {
  const user = useCurrentUser()

  return (
    <AppShell>
      <div className="page__header">
        <div>
          <span className="kicker">MCP · ToolGateway</span>
          <h1>Tool explorer</h1>
        </div>
      </div>
      <p className="page__lead">
        Run a client system's tool through Rootstock exactly as a step of the workflow would. Each step
        (node) may only call the tools on its allowlist; anything else is refused before it reaches the
        client system. Every call, allowed or refused, appears in Langfuse.
      </p>
      {hasRole(user.data, 'ADMIN') ? (
        <ToolExplorer />
      ) : (
        <div className="banner">The Tool explorer is for admins: its results contain client records.</div>
      )}
    </AppShell>
  )
}

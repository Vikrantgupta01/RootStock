import { ChatWindow } from '../components/ChatWindow'
import { HealthBadge } from '../components/HealthBadge'

export function ChatPage() {
  return (
    <main className="page">
      <header className="page__header">
        <h1>RootStock</h1>
        <HealthBadge />
      </header>
      <p className="page__lead">
        Scaffold app — a Spring Boot + Spring AI (AWS Bedrock) backend and this
        React client. Replace this page with the real product.
      </p>
      <ChatWindow />
    </main>
  )
}

import { Link } from 'react-router-dom'
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
        React client. Try the <Link to="/knowledge">knowledge base</Link> for
        document ingestion, tunable RAG profiles, and grounded Q&amp;A.
      </p>
      <ChatWindow />
    </main>
  )
}

import { Link } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { ChatWindow } from '../components/ChatWindow'

export function ChatPage() {
  return (
    <AppShell>
      <div className="page__header">
        <div>
          <span className="kicker">Assistant</span>
          <h1>Chat</h1>
        </div>
      </div>
      <p className="page__lead">
        A Spring Boot backend on AWS Bedrock, and this React client. Replies remember the rest of
        the conversation. For answers grounded in your own documents, use the{' '}
        <Link to="/knowledge">knowledge base</Link>.
      </p>
      <ChatWindow />
    </AppShell>
  )
}

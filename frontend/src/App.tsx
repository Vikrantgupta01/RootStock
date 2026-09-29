import { Navigate, Route, Routes } from 'react-router-dom'
import { LoginScreen } from './components/LoginScreen'
import { useCurrentUser, useHasSession } from './hooks/useSession'
import { ChatPage } from './pages/ChatPage'
import { KnowledgePage } from './pages/KnowledgePage'

export default function App() {
  const hasSession = useHasSession()
  const user = useCurrentUser()

  if (!hasSession) {
    return <LoginScreen />
  }
  // A stored token the backend won't accept (revoked, or a pool change) fails
  // /api/auth/me; the request layer has already cleared it, so ask again.
  if (user.isError) {
    return <LoginScreen />
  }
  if (user.isLoading) {
    return <main className="page"><p className="muted">Signing in…</p></main>
  }

  return (
    <Routes>
      <Route path="/" element={<ChatPage />} />
      <Route path="/knowledge" element={<KnowledgePage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

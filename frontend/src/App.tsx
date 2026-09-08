import { Navigate, Route, Routes } from 'react-router-dom'
import { ChatPage } from './pages/ChatPage'
import { KnowledgePage } from './pages/KnowledgePage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<ChatPage />} />
      <Route path="/knowledge" element={<KnowledgePage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

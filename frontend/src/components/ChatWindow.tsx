import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useChat, useChatTranscript, useDeleteChatConversation } from '../hooks/useChat'

interface Turn {
  role: 'user' | 'assistant'
  text: string
}

/** Which thread this browser was last in. The history itself lives on the server. */
const ACTIVE_KEY = 'rootstock.chat.conversation'

function rememberedConversation(): string | null {
  try {
    return localStorage.getItem(ACTIVE_KEY)
  } catch {
    return null
  }
}

function remember(id: string | null) {
  try {
    if (id) localStorage.setItem(ACTIVE_KEY, id)
    else localStorage.removeItem(ACTIVE_KEY)
  } catch {
    // ignore: storage may be unavailable
  }
}

export function ChatWindow() {
  const [input, setInput] = useState('')
  const [turns, setTurns] = useState<Turn[]>([])
  const [conversationId, setConversationId] = useState<string | null>(rememberedConversation)
  const chat = useChat()
  const remove = useDeleteChatConversation()
  const stored = useChatTranscript(conversationId)

  // Rehydrate the log from the server on load (or when switching threads), so a
  // refresh no longer wipes the conversation -- only the in-page log ever did.
  useEffect(() => {
    if (stored.data) {
      setTurns(stored.data.map((m) => ({ role: m.role === 'USER' ? 'user' : 'assistant', text: m.content })))
    }
  }, [stored.data])

  function open(id: string | null) {
    setConversationId(id)
    remember(id)
    if (!id) setTurns([])
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const message = input.trim()
    if (!message || chat.isPending) return

    setTurns((prev) => [...prev, { role: 'user', text: message }])
    setInput('')

    chat.mutate(
      { message, conversationId },
      {
        onSuccess: (res) => {
          if (res.conversationId !== conversationId) open(res.conversationId)
          setTurns((prev) => [...prev, { role: 'assistant', text: res.reply }])
        },
        onError: (err) =>
          setTurns((prev) => [
            ...prev,
            { role: 'assistant', text: `⚠️ ${err.title}: ${err.detail}` },
          ]),
      },
    )
  }

  return (
    <section className="chat">
      {conversationId && (
        <div className="chat__bar">
          <button className="btn btn--ghost btn--sm" onClick={() => open(null)}>
            New chat
          </button>
          <button
            className="btn btn--ghost btn--sm btn--danger"
            disabled={remove.isPending}
            onClick={() => {
              if (!confirm('Delete this conversation?')) return
              const id = conversationId
              open(null)
              remove.mutate(id)
            }}
          >
            Delete
          </button>
        </div>
      )}

      <div className="chat__log">
        {turns.length === 0 && (
          <p className="chat__empty">
            Ask the assistant something to exercise the AWS Bedrock chat path. Replies remember
            the rest of this conversation.
          </p>
        )}
        {turns.map((turn, i) => (
          <div key={i} className={`chat__turn chat__turn--${turn.role}`}>
            <span className="chat__role">{turn.role}</span>
            <p>{turn.text}</p>
          </div>
        ))}
        {chat.isPending && <div className="chat__turn chat__turn--assistant">…</div>}
      </div>

      <form className="chat__form" onSubmit={onSubmit}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Type a message"
          aria-label="Message"
        />
        <button type="submit" disabled={chat.isPending}>
          Send
        </button>
      </form>
    </section>
  )
}

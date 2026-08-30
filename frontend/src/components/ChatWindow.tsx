import { useState } from 'react'
import type { FormEvent } from 'react'
import { useChat } from '../hooks/useChat'

interface Turn {
  role: 'user' | 'assistant'
  text: string
}

export function ChatWindow() {
  const [input, setInput] = useState('')
  const [turns, setTurns] = useState<Turn[]>([])
  const chat = useChat()

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const message = input.trim()
    if (!message || chat.isPending) return

    setTurns((prev) => [...prev, { role: 'user', text: message }])
    setInput('')

    chat.mutate(message, {
      onSuccess: (res) =>
        setTurns((prev) => [...prev, { role: 'assistant', text: res.reply }]),
      onError: (err) =>
        setTurns((prev) => [
          ...prev,
          { role: 'assistant', text: `⚠️ ${err.title}: ${err.detail}` },
        ]),
    })
  }

  return (
    <section className="chat">
      <div className="chat__log">
        {turns.length === 0 && (
          <p className="chat__empty">
            Ask the assistant something to exercise the AWS Bedrock chat path.
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

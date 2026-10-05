import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import type { AgentStep } from '../api/agent'
import { useAgent, useAgentTranscript, useDeleteAgentConversation } from '../hooks/useAgent'
import { rememberConversation, rememberedConversation } from '../lib/activeConversation'

interface Turn {
  role: 'user' | 'assistant'
  text: string
  /**
   * How the answer was reached. Only answers given in this page view have it:
   * the server keeps questions and answers, not the working in between.
   */
  steps?: AgentStep[]
  iterations?: number
}

const ACTIVE_KEY = 'rootstock.agent.conversation'

export function AgentWindow() {
  const [input, setInput] = useState('')
  const [turns, setTurns] = useState<Turn[]>([])
  const [conversationId, setConversationId] = useState<string | null>(() =>
    rememberedConversation(ACTIVE_KEY),
  )
  const agent = useAgent()
  const remove = useDeleteAgentConversation()
  const stored = useAgentTranscript(conversationId)
  // The thread whose stored transcript is already on screen. The transcript is
  // loaded once per thread opened, never again: it has no steps, so a later
  // refetch (window refocus, or the id arriving with the first answer) would
  // replace this page's turns and wipe the steps they carry.
  const loadedFor = useRef<string | null>(null)

  useEffect(() => {
    if (stored.data && conversationId && loadedFor.current !== conversationId) {
      loadedFor.current = conversationId
      setTurns(stored.data.map((m) => ({ role: m.role === 'USER' ? 'user' : 'assistant', text: m.content })))
    }
  }, [stored.data, conversationId])

  function open(id: string | null) {
    setConversationId(id)
    rememberConversation(ACTIVE_KEY, id)
    if (!id) setTurns([])
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    const message = input.trim()
    if (!message || agent.isPending) return

    setTurns((prev) => [...prev, { role: 'user', text: message }])
    setInput('')

    agent.mutate(
      { message, conversationId },
      {
        onSuccess: (res) => {
          setTurns((prev) => [
            ...prev,
            { role: 'assistant', text: res.answer, steps: res.steps, iterations: res.iterations },
          ])
          if (res.conversationId !== conversationId) {
            // This page already shows the thread it just started.
            loadedFor.current = res.conversationId
            setConversationId(res.conversationId)
            rememberConversation(ACTIVE_KEY, res.conversationId)
          }
        },
        onError: (err) =>
          setTurns((prev) => [...prev, { role: 'assistant', text: `⚠️ ${err.title}: ${err.detail}` }]),
      },
    )
  }

  return (
    <section className="chat">
      {conversationId && (
        <div className="chat__bar">
          <button className="btn btn--ghost btn--sm" onClick={() => open(null)}>
            New conversation
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
            Ask something that needs a lookup or two, like “What's today's date, and what does our
            refund policy say?” Expand an answer's steps to see what the agent searched for and found.
          </p>
        )}
        {turns.map((turn, i) => (
          <div key={i} className={`chat__turn chat__turn--${turn.role}`}>
            <span className="chat__role">{turn.role === 'assistant' ? 'agent' : 'you'}</span>
            {turn.steps && <Trace steps={turn.steps} iterations={turn.iterations ?? 0} />}
            <p>{turn.text}</p>
          </div>
        ))}
        {agent.isPending && (
          <div className="chat__turn chat__turn--assistant">
            <span className="chat__role">agent</span>
            <p className="agent__thinking">Reasoning and calling tools…</p>
          </div>
        )}
      </div>

      <form className="chat__form" onSubmit={onSubmit}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Ask the agent"
          aria-label="Message"
        />
        <button type="submit" disabled={agent.isPending}>
          Send
        </button>
      </form>
    </section>
  )
}

/** The Reason → Act → Observe record behind one answer, collapsed by default. */
function Trace({ steps, iterations }: { steps: AgentStep[]; iterations: number }) {
  if (steps.length === 0) {
    return <span className="agent__summary agent__summary--plain">Answered directly, no tools used</span>
  }
  const calls = steps.length === 1 ? '1 tool call' : `${steps.length} tool calls`
  const rounds = iterations === 1 ? '1 step' : `${iterations} steps`
  return (
    <details className="agent__trace">
      <summary className="agent__summary">
        {calls} · {rounds}
      </summary>
      <ol className="agent__steps">
        {steps.map((step, i) => (
          <li key={i} className="agent__step">
            {step.thought && (
              <p className="agent__thought">
                <span className="agent__label">Reason {step.iteration}</span>
                {step.thought}
              </p>
            )}
            <p className="agent__call">
              <span className="agent__label">Act</span>
              <code>
                {step.tool}({formatArguments(step.input)})
              </code>
            </p>
            <div className="agent__observation">
              <span className="agent__label">Observe</span>
              <pre>{step.observation || '(empty)'}</pre>
            </div>
          </li>
        ))}
      </ol>
    </details>
  )
}

/** `{"query":"refund policy"}` reads better as `"refund policy"`; anything else stays as sent. */
function formatArguments(raw: string): string {
  try {
    const args = JSON.parse(raw) as unknown
    if (args && typeof args === 'object' && !Array.isArray(args)) {
      const values = Object.values(args)
      if (values.length === 0) return ''
      if (values.length === 1) return JSON.stringify(values[0])
    }
  } catch {
    // not JSON; show it raw
  }
  return raw
}

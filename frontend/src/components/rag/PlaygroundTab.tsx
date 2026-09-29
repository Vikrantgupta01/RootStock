import { useState } from 'react'
import { useProfiles, useRagQuery } from '../../hooks/rag'

export function PlaygroundTab() {
  const profiles = useProfiles()
  const query = useRagQuery()

  // Follow-ups go back into the same thread, so "and what about its price?"
  // resolves against what was already asked. Kept in state only -- the history
  // itself lives on the server.
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [question, setQuestion] = useState('')
  const [profileId, setProfileId] = useState<string>('')
  const [override, setOverride] = useState(false)
  const [topK, setTopK] = useState(4)
  const [threshold, setThreshold] = useState(0.5)

  function ask() {
    if (!question.trim()) return
    query.mutate(
      {
        question: question.trim(),
        conversationId,
        profileId: profileId || null,
        topK: override ? topK : null,
        similarityThreshold: override ? threshold : null,
      },
      { onSuccess: (res) => setConversationId(res.conversationId) },
    )
    setQuestion('')
  }

  const answer = query.data
  // Only worth showing when the rewrite changed something -- which only happens
  // on a follow-up that couldn't stand on its own.
  const lastAsked = query.variables?.question

  return (
    <div className="playground">
      <div className="playground__controls">
        <label>
          Profile{' '}
          <select value={profileId} onChange={(e) => setProfileId(e.target.value)}>
            <option value="">active profile</option>
            {profiles.data?.map((p) => (
              <option key={p.name} value={p.id}>
                {p.name} — v{p.versionNo}
              </option>
            ))}
          </select>
        </label>
        <label className="checkline">
          <input type="checkbox" checked={override} onChange={(e) => setOverride(e.target.checked)} />
          override retrieval
        </label>
        {conversationId && (
          <button className="btn btn--ghost btn--sm" onClick={() => setConversationId(null)}>
            new thread
          </button>
        )}
        {override && (
          <>
            <label className="inline-range">
              top-k {topK}
              <input type="range" min={1} max={20} value={topK} onChange={(e) => setTopK(Number(e.target.value))} />
            </label>
            <label className="inline-range">
              threshold {threshold.toFixed(2)}
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={threshold}
                onChange={(e) => setThreshold(Number(e.target.value))}
              />
            </label>
          </>
        )}
      </div>

      <textarea
        className="playground__input"
        rows={3}
        placeholder="Ask a question about the indexed documents…"
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) ask()
        }}
      />
      <div className="actions">
        <button className="btn btn--primary" disabled={query.isPending || !question.trim()} onClick={ask}>
          {query.isPending ? 'Thinking…' : 'Ask'}
        </button>
        <span className="muted small">⌘/Ctrl + Enter</span>
      </div>

      {query.isError && (
        <div className="banner banner--error">
          {query.error.status === 503
            ? 'The chat model is not available. Set AWS Bedrock credentials and a granted model id (BEDROCK_MODEL).'
            : `${query.error.title}: ${query.error.detail}`}
        </div>
      )}

      {answer && (
        <div className="answer">
          <div className="answer__meta">
            <span className={`badge badge--${answer.grounded ? 'ok' : 'muted'}`}>
              {answer.grounded ? 'grounded' : 'no matching context'}
            </span>
            <span className="muted small">
              {answer.profileName} v{answer.profileVersionNo} · {answer.usedVersionIds.length} document
              version(s)
            </span>
          </div>
          {answer.retrievalQuery !== lastAsked && (
            <p className="muted small">
              Searched for: <em>{answer.retrievalQuery}</em>
            </p>
          )}
          <p className="answer__text">{answer.answer}</p>

          {answer.citations.length > 0 && (
            <ol className="citations">
              {answer.citations.map((c) => (
                <li key={c.rank}>
                  <div className="citations__head">
                    <span className="pill">[{c.rank}]</span>
                    <strong>{c.sourceKey ?? c.displayName ?? 'unknown'}</strong>
                    {c.versionNo != null && <span className="muted"> v{c.versionNo}</span>}
                    {c.score != null && <span className="muted small"> · {c.score.toFixed(3)}</span>}
                  </div>
                  {c.snippet && <p className="citations__snippet">{c.snippet}</p>}
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </div>
  )
}

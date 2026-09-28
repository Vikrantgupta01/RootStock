import { useState, type ReactNode } from 'react'
import type { ProfileUpdate, RagProfile } from '../../api/rag'
import { useActivateProfile, useCreateProfile, useProfileVersions, useProfiles, useUpdateProfile } from '../../hooks/rag'
import { formatDate } from '../../lib/format'
import { StatusBadge } from './StatusBadge'

// The only Bedrock reranking model currently available in this account/region.
// Extend to a dropdown if/when more become available.
const RERANKER_MODEL_ID = 'cohere.rerank-v3-5:0'

type FormState = Required<Omit<ProfileUpdate, 'rerankerModel'>>

function toForm(p: RagProfile): FormState {
  return {
    chatModelId: p.chatModelId,
    topK: p.topK,
    similarityThreshold: p.similarityThreshold,
    rerankerEnabled: p.rerankerEnabled,
    maxContextTokens: p.maxContextTokens,
    promptTemplate: p.promptTemplate,
  }
}

function toBody(form: FormState): ProfileUpdate {
  return { ...form, rerankerModel: form.rerankerEnabled ? RERANKER_MODEL_ID : null }
}

export function TuningTab() {
  const profiles = useProfiles()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [form, setForm] = useState<FormState | null>(null)
  const [formKey, setFormKey] = useState('')

  const create = useCreateProfile()
  const update = useUpdateProfile()
  const activate = useActivateProfile()
  const versions = useProfileVersions(selectedId)

  const selected = profiles.data?.find((p) => p.id === selectedId) ?? profiles.data?.[0] ?? null

  // Re-seed the form when the selected profile (or its version) changes. Setting
  // state during render is the supported pattern for "reset on prop change".
  if (selected) {
    const key = `${selected.id}:${selected.versionNo}`
    if (key !== formKey) {
      setFormKey(key)
      setForm(toForm(selected))
      if (selectedId !== selected.id) setSelectedId(selected.id)
    }
  }

  if (profiles.isLoading || !selected || !form) return <p className="muted">Loading profiles…</p>

  const dirty = JSON.stringify(form) !== JSON.stringify(toForm(selected))

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => (f ? { ...f, [key]: value } : f))
  }

  async function saveVersion(thenActivate: boolean) {
    if (!selected || !form) return
    const created = (await update.mutateAsync({ id: selected.id, body: toBody(form) })) as RagProfile
    setSelectedId(created.id)
    if (thenActivate) {
      await activate.mutateAsync(created.id)
    }
  }

  async function newProfile() {
    const name = prompt('Name for the new profile')?.trim()
    if (!name || !form) return
    const created = (await create.mutateAsync({ name, ...toBody(form) })) as RagProfile
    setSelectedId(created.id)
  }

  async function activateVersion(id: string) {
    await activate.mutateAsync(id)
    setSelectedId(id)
  }

  return (
    <div className="tuning">
      <div className="tuning__bar">
        <label>
          Profile{' '}
          <select value={selected.id} onChange={(e) => setSelectedId(e.target.value)}>
            {profiles.data!.map((p) => (
              <option key={p.name} value={p.id}>
                {p.name} — v{p.versionNo} {p.active ? '(active)' : ''}
              </option>
            ))}
          </select>
        </label>
        <button className="btn btn--ghost" onClick={newProfile} disabled={create.isPending}>
          + new profile
        </button>
      </div>

      <div className="grid">
        <Field label={`Top-k (${form.topK})`}>
          <input
            type="range"
            min={1}
            max={20}
            value={form.topK}
            onChange={(e) => set('topK', Number(e.target.value))}
          />
        </Field>
        <Field label={`Similarity threshold (${form.similarityThreshold.toFixed(2)})`}>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={form.similarityThreshold}
            onChange={(e) => set('similarityThreshold', Number(e.target.value))}
          />
        </Field>
        <Field label="Reranker">
          <label className="checkline">
            <input
              type="checkbox"
              checked={form.rerankerEnabled}
              onChange={(e) => set('rerankerEnabled', e.target.checked)}
            />
            enabled ({RERANKER_MODEL_ID})
          </label>
        </Field>
        <Field label="Chat model override">
          <input
            type="text"
            placeholder="e.g. us.anthropic.claude-sonnet-4-5-20250929-v1:0"
            value={form.chatModelId ?? ''}
            onChange={(e) => set('chatModelId', e.target.value || null)}
          />
        </Field>
        <Field label={`Max context tokens (${form.maxContextTokens})`}>
          <input
            type="number"
            min={256}
            max={200000}
            step={256}
            value={form.maxContextTokens}
            onChange={(e) => set('maxContextTokens', Number(e.target.value))}
          />
        </Field>
      </div>

      <p className="hint">
        Chat model override and max context tokens are saved with the profile
        but not applied yet — every query still uses the server's single
        configured chat model with no context-token budget. Coming in a future
        release; values you set now will carry over once that's wired up.
      </p>

      <Field label="Prompt template — must contain {context} and {question}">
        <textarea
          rows={8}
          value={form.promptTemplate}
          onChange={(e) => set('promptTemplate', e.target.value)}
        />
      </Field>

      <div className="actions">
        <button className="btn" disabled={!dirty || update.isPending} onClick={() => saveVersion(false)}>
          Save version
        </button>
        <button
          className="btn btn--primary"
          disabled={update.isPending || activate.isPending}
          onClick={() => saveVersion(true)}
        >
          {dirty ? 'Save & activate' : 'Activate this profile'}
        </button>
      </div>

      <h3>Version history</h3>
      {versions.data && (
        <table className="table table--nested">
          <thead>
            <tr>
              <th>v#</th>
              <th>Top-k</th>
              <th>Similarity</th>
              <th>Created</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {versions.data.map((v) => (
              <tr key={v.id}>
                <td>
                  {v.versionNo}
                  {v.active && <StatusBadge status="COMPLETED" />}
                </td>
                <td>{v.topK}</td>
                <td className="muted">{v.similarityThreshold.toFixed(2)}</td>
                <td className="muted">{formatDate(v.createdAt)}</td>
                <td>
                  {!v.active && (
                    <button
                      className="btn btn--sm"
                      disabled={activate.isPending}
                      onClick={() => activateVersion(v.id)}
                    >
                      activate
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field__label">{label}</span>
      {children}
    </label>
  )
}

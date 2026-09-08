import { useEffect, useState, type ReactNode } from 'react'
import type { ChunkingStrategy, ProfileUpdate, RagProfile } from '../../api/rag'
import {
  useActivateProfile,
  useActivation,
  useCreateProfile,
  useProfileVersions,
  useProfiles,
  useUpdateProfile,
} from '../../hooks/rag'
import { formatDate } from '../../lib/format'
import { StatusBadge } from './StatusBadge'

type FormState = Required<Omit<ProfileUpdate, 'chatModelId' | 'rerankerModel'>>

const EMBEDDING_MODELS = ['fake', 'bedrock-titan-v2']
const STRATEGIES: ChunkingStrategy[] = ['CHARACTER', 'TOKEN', 'SEMANTIC']

function toForm(p: RagProfile): FormState {
  return {
    chunkingStrategy: p.chunkingStrategy,
    chunkSize: p.chunkSize,
    chunkOverlap: p.chunkOverlap,
    embeddingModelId: p.embeddingModelId,
    topK: p.topK,
    similarityThreshold: p.similarityThreshold,
    maxContextTokens: p.maxContextTokens,
    rerankerEnabled: p.rerankerEnabled,
    promptTemplate: p.promptTemplate,
    hybridSearch: p.hybridSearch,
  }
}

const LAYOUT_KEYS: (keyof FormState)[] = ['chunkingStrategy', 'chunkSize', 'chunkOverlap', 'embeddingModelId']

export function TuningTab() {
  const profiles = useProfiles()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [form, setForm] = useState<FormState | null>(null)
  const [formKey, setFormKey] = useState('')
  const [activationId, setActivationId] = useState<string | null>(null)

  const create = useCreateProfile()
  const update = useUpdateProfile()
  const activate = useActivateProfile()
  const activation = useActivation(activationId)
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

  useEffect(() => {
    if (activation.data && activation.data.state !== 'PENDING') {
      const timer = setTimeout(() => setActivationId(null), 4000)
      return () => clearTimeout(timer)
    }
  }, [activation.data])

  if (profiles.isLoading || !selected || !form) return <p className="muted">Loading profiles…</p>

  const layoutChanged = LAYOUT_KEYS.some((k) => form[k] !== toForm(selected)[k])
  const dirty = JSON.stringify(form) !== JSON.stringify(toForm(selected))

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((f) => (f ? { ...f, [key]: value } : f))
  }

  async function saveVersion(thenActivate: boolean) {
    if (!selected || !form) return
    const created = (await update.mutateAsync({ id: selected.id, body: form })) as RagProfile
    setSelectedId(created.id)
    if (thenActivate) {
      const result = await activate.mutateAsync(created.id)
      if (result.reindexRequired && result.state === 'PENDING' && result.activationId) {
        setActivationId(result.activationId)
      }
    }
  }

  async function newProfile() {
    const name = prompt('Name for the new profile')?.trim()
    if (!name || !form) return
    const created = (await create.mutateAsync({ name, ...form })) as RagProfile
    setSelectedId(created.id)
  }

  async function activateVersion(id: string) {
    const result = await activate.mutateAsync(id)
    setSelectedId(id)
    if (result.reindexRequired && result.state === 'PENDING' && result.activationId) {
      setActivationId(result.activationId)
    }
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

      {activationId && activation.data && (
        <div className={`banner banner--${activation.data.state === 'FAILED' ? 'error' : 'busy'}`}>
          {activation.data.state === 'PENDING'
            ? `Re-indexing ${activation.data.totalReindexJobs} document version(s) under the new profile — the previous one keeps serving until it finishes.`
            : activation.data.state === 'FAILED'
              ? `Activation failed: ${activation.data.errorMessage ?? 'see the Activity tab'}`
              : 'Activation complete — the new profile is live.'}
        </div>
      )}

      <div className="grid">
        <Field label="Chunking strategy">
          <select
            value={form.chunkingStrategy}
            onChange={(e) => set('chunkingStrategy', e.target.value as ChunkingStrategy)}
          >
            {STRATEGIES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Embedding model">
          <select value={form.embeddingModelId} onChange={(e) => set('embeddingModelId', e.target.value)}>
            {EMBEDDING_MODELS.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </Field>
        <Field label={`Chunk size (${form.chunkSize} chars)`}>
          <input
            type="number"
            min={200}
            max={8000}
            value={form.chunkSize}
            onChange={(e) => set('chunkSize', Number(e.target.value))}
          />
        </Field>
        <Field label={`Chunk overlap (${form.chunkOverlap} chars)`}>
          <input
            type="number"
            min={0}
            max={2000}
            value={form.chunkOverlap}
            onChange={(e) => set('chunkOverlap', Number(e.target.value))}
          />
        </Field>
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
        <Field label="Reranker">
          <label className="checkline">
            <input
              type="checkbox"
              checked={form.rerankerEnabled}
              onChange={(e) => set('rerankerEnabled', e.target.checked)}
            />
            enabled
          </label>
        </Field>
      </div>

      <Field label="Prompt template — must contain {context} and {question}">
        <textarea
          rows={8}
          value={form.promptTemplate}
          onChange={(e) => set('promptTemplate', e.target.value)}
        />
      </Field>

      {layoutChanged && (
        <p className="hint hint--warn">
          Chunking / embedding change — activating this will trigger a blue/green re-index.
        </p>
      )}

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
              <th>Chunking</th>
              <th>Embedding</th>
              <th>Top-k</th>
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
                <td className="muted">
                  {v.chunkingStrategy} {v.chunkSize}/{v.chunkOverlap}
                </td>
                <td className="muted">{v.embeddingModelId}</td>
                <td>{v.topK}</td>
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

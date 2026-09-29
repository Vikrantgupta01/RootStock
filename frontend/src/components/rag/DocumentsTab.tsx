import { Fragment, useState } from 'react'
import { rag, type DocumentVersion } from '../../api/rag'
import { useAccessGroups, useSetDocumentAccessGroups } from '../../hooks/access'
import { hasRole, useCurrentUser } from '../../hooks/useSession'
import {
  useAddVersion,
  useActivateVersion,
  useDeleteDocument,
  useDeleteVersion,
  useDocument,
  useDocuments,
  useReindexVersion,
  useUploadDocument,
} from '../../hooks/rag'
import { formatBytes, formatDate } from '../../lib/format'
import { Dropzone } from './Dropzone'
import { StatusBadge } from './StatusBadge'

interface UploadRow {
  key: string
  name: string
  fraction: number
  error?: string
}

export function DocumentsTab() {
  const user = useCurrentUser()
  // Viewers can read the library and query it; changing it is EDITOR and up, and
  // the backend enforces the same rule -- hiding the controls just avoids
  // offering an action that would come back 403.
  const canEdit = hasRole(user.data, 'ADMIN', 'EDITOR')
  const documents = useDocuments()
  const upload = useUploadDocument()
  const [uploads, setUploads] = useState<UploadRow[]>([])
  const [expanded, setExpanded] = useState<string | null>(null)

  async function handleFiles(files: File[]) {
    for (const file of files) {
      const key = `${file.name}-${Date.now()}-${Math.random()}`
      setUploads((u) => [...u, { key, name: file.name, fraction: 0 }])
      try {
        await upload.mutateAsync({
          file,
          onProgress: (f) => setUploads((u) => u.map((r) => (r.key === key ? { ...r, fraction: f } : r))),
        })
        setUploads((u) => u.filter((r) => r.key !== key))
      } catch (e) {
        const msg = (e as { detail?: string }).detail ?? 'Upload failed'
        setUploads((u) => u.map((r) => (r.key === key ? { ...r, error: msg } : r)))
      }
    }
  }

  return (
    <div className="stack">
      {canEdit ? (
        <Dropzone onFiles={handleFiles} disabled={upload.isPending && uploads.length > 3} />
      ) : (
        <p className="muted">You have read-only access to this knowledge base.</p>
      )}

      {uploads.length > 0 && (
        <ul className="uploads">
          {uploads.map((u) => (
            <li key={u.key} className={u.error ? 'uploads__row uploads__row--error' : 'uploads__row'}>
              <span className="uploads__name">{u.name}</span>
              {u.error ? (
                <span className="uploads__err">{u.error}</span>
              ) : (
                <span className="uploads__bar">
                  <span className="uploads__fill" style={{ width: `${Math.round(u.fraction * 100)}%` }} />
                </span>
              )}
              {u.error && (
                <button className="btn btn--ghost btn--sm" onClick={() => setUploads((x) => x.filter((r) => r.key !== u.key))}>
                  dismiss
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {documents.isLoading && <p className="muted">Loading documents…</p>}
      {documents.isError && <p className="error-text">Couldn't load documents.</p>}
      {documents.data && documents.data.content.length === 0 && (
        <p className="muted">No documents yet. Drop a file above to get started.</p>
      )}

      {documents.data && documents.data.content.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Document</th>
              <th>Type</th>
              <th>Versions</th>
              <th>Active</th>
              <th>Access</th>
              <th>Updated</th>
              <th aria-label="expand" />
            </tr>
          </thead>
          <tbody>
            {documents.data.content.map((d) => (
              <Fragment key={d.id}>
                <tr
                  className="table__row table__row--clickable"
                  onClick={() => setExpanded(expanded === d.id ? null : d.id)}
                >
                  <td>
                    <strong>{d.displayName}</strong>
                    {d.sourceKey !== d.displayName && <span className="muted"> · {d.sourceKey}</span>}
                  </td>
                  <td className="muted">{d.contentType ?? '—'}</td>
                  <td>{d.versionCount}</td>
                  <td>
                    <StatusBadge status={d.activeStatus} />
                  </td>
                  <td>
                    {d.accessGroups.length === 0 ? (
                      <span className="muted small">everyone</span>
                    ) : (
                      d.accessGroups.map((g) => (
                        <span key={g} className="pill">
                          {g}
                        </span>
                      ))
                    )}
                  </td>
                  <td className="muted">{formatDate(d.updatedAt)}</td>
                  <td>{expanded === d.id ? '▾' : '▸'}</td>
                </tr>
                {expanded === d.id && (
                  <tr>
                    <td colSpan={7} className="table__detail">
                      <DocumentDetailPanel id={d.id} />
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function DocumentDetailPanel({ id }: { id: string }) {
  const user = useCurrentUser()
  const canEdit = hasRole(user.data, 'ADMIN', 'EDITOR')
  const isAdmin = hasRole(user.data, 'ADMIN')
  const detail = useDocument(id)
  const addVersion = useAddVersion()
  const activate = useActivateVersion()
  const reindex = useReindexVersion()
  const deleteVersion = useDeleteVersion()
  const deleteDocument = useDeleteDocument()
  const [progress, setProgress] = useState<number | null>(null)

  if (detail.isLoading || !detail.data) return <p className="muted">Loading versions…</p>
  const doc = detail.data

  async function onAddVersion(files: File[]) {
    const file = files[0]
    if (!file) return
    setProgress(0)
    try {
      await addVersion.mutateAsync({ id, file, onProgress: setProgress })
    } finally {
      setProgress(null)
    }
  }

  return (
    <div className="stack">
      <table className="table table--nested">
        <thead>
          <tr>
            <th>v#</th>
            <th>Status</th>
            <th>Size</th>
            <th>Chunks</th>
            <th>Indexed</th>
            <th aria-label="actions" />
          </tr>
        </thead>
        <tbody>
          {doc.versions.map((v: DocumentVersion) => (
            <tr key={v.id}>
              <td>
                {v.versionNo}
                {v.active && <span className="pill">active</span>}
              </td>
              <td>
                <StatusBadge status={v.status} />
                {v.errorMessage && <div className="error-text small">{v.errorMessage}</div>}
              </td>
              <td className="muted">{formatBytes(v.sizeBytes)}</td>
              <td>{v.chunkCount}</td>
              <td className="muted">{formatDate(v.indexedAt)}</td>
              <td className="row-actions">
                {canEdit && !v.active && (
                  <button
                    className="btn btn--sm"
                    disabled={activate.isPending}
                    onClick={() => activate.mutate({ id, versionNo: v.versionNo })}
                  >
                    activate
                  </button>
                )}
                {canEdit && (
                  <button
                    className="btn btn--ghost btn--sm"
                    disabled={reindex.isPending}
                    onClick={() => reindex.mutate({ id, versionNo: v.versionNo })}
                  >
                    reindex
                  </button>
                )}
                <button
                  className="btn btn--ghost btn--sm"
                  onClick={() => rag.documents.download(id, v.versionNo, doc.displayName)}
                >
                  download
                </button>
                {canEdit && (
                  <button
                    className="btn btn--ghost btn--sm btn--danger"
                    disabled={deleteVersion.isPending}
                    onClick={() => deleteVersion.mutate({ id, versionNo: v.versionNo })}
                  >
                    delete
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {isAdmin && <AccessGroupEditor documentId={id} current={doc.accessGroups} />}

      {canEdit && (
        <div className="detail-footer">
          {progress === null ? (
            <Dropzone onFiles={onAddVersion} hint="drop or click to upload a new version of this document" />
          ) : (
            <span className="uploads__bar">
              <span className="uploads__fill" style={{ width: `${Math.round(progress * 100)}%` }} />
            </span>
          )}
          <button
            className="btn btn--ghost btn--danger"
            disabled={deleteDocument.isPending}
            onClick={() => {
              if (confirm(`Delete "${doc.displayName}" and all its versions?`)) deleteDocument.mutate(id)
            }}
          >
            delete document
          </button>
        </div>
      )}
    </div>
  )
}

/**
 * Which groups may retrieve this document. Saving re-syncs the knowledge base,
 * because Bedrock filters on its own copy of the grants and only re-reads them
 * on the next ingestion job -- until then the previous grants still apply.
 */
function AccessGroupEditor({ documentId, current }: { documentId: string; current: string[] }) {
  const groups = useAccessGroups()
  const save = useSetDocumentAccessGroups()
  const [selected, setSelected] = useState<string[]>(current)

  const dirty =
    selected.length !== current.length || selected.some((g) => !current.includes(g))

  function toggle(group: string) {
    setSelected((s) => (s.includes(group) ? s.filter((g) => g !== group) : [...s, group]))
  }

  return (
    <div className="stack access-editor">
      <h4>Access</h4>
      {groups.data && groups.data.length === 0 && (
        <p className="muted small">No groups exist yet — create one on the Access tab.</p>
      )}
      {groups.data && groups.data.length > 0 && (
        <div className="pill-list">
          {groups.data.map((g) => (
            <label key={g} className={`pill pill--toggle${selected.includes(g) ? ' pill--on' : ''}`}>
              <input type="checkbox" checked={selected.includes(g)} onChange={() => toggle(g)} />
              {g}
            </label>
          ))}
        </div>
      )}
      <p className="muted small">
        {selected.length === 0
          ? 'Visible to everyone in this tenant.'
          : `Only retrievable by members of ${selected.join(', ')} (and admins).`}
      </p>
      <button
        className="btn btn--sm"
        disabled={!dirty || save.isPending}
        onClick={() => save.mutate({ documentId, groups: selected })}
      >
        {save.isPending ? 'Saving…' : 'Save access & re-sync'}
      </button>
    </div>
  )
}

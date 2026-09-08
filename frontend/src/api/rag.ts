// Typed client for the RAG subsystem. Every call carries the X-Tenant-Id header.

import { BASE_URL, request, toApiError, type ApiError } from './http'
import { getTenant } from './tenant'

// ---- types ------------------------------------------------------------------

export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'INDEXED' | 'FAILED' | 'SUPERSEDED'
export type JobKind = 'INGEST' | 'REINDEX' | 'CLEANUP'
export type JobState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type ChunkingStrategy = 'CHARACTER' | 'TOKEN' | 'SEMANTIC'
export type ActivationState = 'PENDING' | 'COMPLETED' | 'FAILED'

export interface Page<T> {
  content: T[]
  page: { size: number; number: number; totalElements: number; totalPages: number }
}

export interface DocumentVersion {
  id: string
  versionNo: number
  status: DocumentStatus
  sizeBytes: number
  contentHash: string
  chunkCount: number
  active: boolean
  errorMessage: string | null
  createdAt: string
  indexedAt: string | null
}

export interface DocumentSummary {
  id: string
  sourceKey: string
  displayName: string
  contentType: string | null
  activeVersionId: string | null
  versionCount: number
  activeStatus: DocumentStatus | null
  createdAt: string
  updatedAt: string
}

export interface DocumentDetail {
  id: string
  sourceKey: string
  displayName: string
  contentType: string | null
  activeVersionId: string | null
  createdAt: string
  updatedAt: string
  versions: DocumentVersion[]
}

export interface UploadResult {
  documentId: string
  version: DocumentVersion
}

export interface IngestionJob {
  id: string
  kind: JobKind
  state: JobState
  documentVersionId: string | null
  profileId: string | null
  attempts: number
  maxAttempts: number
  lockedBy: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface RagProfile {
  id: string
  name: string
  versionNo: number
  active: boolean
  chunkingStrategy: ChunkingStrategy
  chunkSize: number
  chunkOverlap: number
  embeddingModelId: string
  chatModelId: string | null
  topK: number
  similarityThreshold: number
  rerankerEnabled: boolean
  rerankerModel: string | null
  maxContextTokens: number
  promptTemplate: string
  hybridSearch: boolean
  fineTunedModelArn: string | null
  createdAt: string
}

export interface ProfileUpdate {
  chunkingStrategy?: ChunkingStrategy
  chunkSize?: number
  chunkOverlap?: number
  embeddingModelId?: string
  chatModelId?: string | null
  topK?: number
  similarityThreshold?: number
  rerankerEnabled?: boolean
  rerankerModel?: string | null
  maxContextTokens?: number
  promptTemplate?: string
  hybridSearch?: boolean
}

export interface ProfileCreate extends ProfileUpdate {
  name: string
}

export interface Activation {
  activationId: string | null
  targetProfileId: string
  previousProfileId: string | null
  reindexRequired: boolean
  totalReindexJobs: number
  state: ActivationState
  errorMessage: string | null
  createdAt: string
  completedAt: string | null
}

export interface Citation {
  rank: number
  documentId: string | null
  sourceKey: string | null
  displayName: string | null
  versionNo: number | null
  chunkIndex: number | null
  score: number | null
  snippet: string | null
}

export interface RagAnswer {
  answer: string
  grounded: boolean
  citations: Citation[]
  profileId: string
  profileName: string
  profileVersionNo: number
  usedVersionIds: string[]
}

export interface QueryRequest {
  question: string
  profileId?: string | null
  topK?: number | null
  similarityThreshold?: number | null
}

// ---- multipart upload with progress --------------------------------------- -

function xhrUpload<T>(path: string, form: FormData, onProgress?: (fraction: number) => void): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${BASE_URL}${path}`)
    xhr.setRequestHeader('X-Tenant-Id', getTenant())
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total)
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.responseText ? (JSON.parse(xhr.responseText) as T) : (undefined as T))
        return
      }
      let err: ApiError = { status: xhr.status, title: 'Upload failed', detail: xhr.statusText }
      try {
        const b = JSON.parse(xhr.responseText) as Partial<ApiError>
        err = { status: xhr.status, title: b.title ?? err.title, detail: b.detail ?? err.detail }
      } catch {
        // keep default
      }
      reject(err)
    }
    xhr.onerror = () => reject({ status: 0, title: 'Network error', detail: 'The upload failed.' } as ApiError)
    xhr.send(form)
  })
}

// ---- API ----------------------------------------------------------------- -

const t = { tenant: true } as const

export const rag = {
  documents: {
    list: (pageNo = 0, size = 50) =>
      request<Page<DocumentSummary>>(`/api/rag/documents?page=${pageNo}&size=${size}`, {}, t),

    get: (id: string) => request<DocumentDetail>(`/api/rag/documents/${id}`, {}, t),

    upload: (
      file: File,
      opts: { sourceKey?: string; displayName?: string; onProgress?: (f: number) => void } = {},
    ) => {
      const form = new FormData()
      form.append('file', file)
      if (opts.sourceKey) form.append('sourceKey', opts.sourceKey)
      if (opts.displayName) form.append('displayName', opts.displayName)
      return xhrUpload<UploadResult>('/api/rag/documents', form, opts.onProgress)
    },

    addVersion: (id: string, file: File, onProgress?: (f: number) => void) => {
      const form = new FormData()
      form.append('file', file)
      return xhrUpload<UploadResult>(`/api/rag/documents/${id}/versions`, form, onProgress)
    },

    activateVersion: (id: string, versionNo: number) =>
      request<DocumentDetail>(`/api/rag/documents/${id}/versions/${versionNo}/activate`, { method: 'POST' }, t),

    reindexVersion: (id: string, versionNo: number) =>
      request<DocumentVersion>(`/api/rag/documents/${id}/versions/${versionNo}/reindex`, { method: 'POST' }, t),

    remove: (id: string) => request<void>(`/api/rag/documents/${id}`, { method: 'DELETE' }, t),

    removeVersion: (id: string, versionNo: number) =>
      request<void>(`/api/rag/documents/${id}/versions/${versionNo}`, { method: 'DELETE' }, t),

    download: async (id: string, versionNo: number, filename: string) => {
      const res = await fetch(`${BASE_URL}/api/rag/documents/${id}/versions/${versionNo}/content`, {
        headers: { 'X-Tenant-Id': getTenant() },
      })
      if (!res.ok) throw await toApiError(res)
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      a.click()
      URL.revokeObjectURL(url)
    },
  },

  jobs: {
    list: (state: JobState | 'ALL' = 'ALL', pageNo = 0, size = 50) => {
      const q = state === 'ALL' ? '' : `&state=${state}`
      return request<Page<IngestionJob>>(`/api/rag/jobs?page=${pageNo}&size=${size}${q}`, {}, t)
    },
  },

  profiles: {
    list: () => request<RagProfile[]>('/api/rag/profiles', {}, t),
    get: (id: string) => request<RagProfile>(`/api/rag/profiles/${id}`, {}, t),
    versions: (id: string) => request<RagProfile[]>(`/api/rag/profiles/${id}/versions`, {}, t),
    create: (body: ProfileCreate) =>
      request<RagProfile>('/api/rag/profiles', { method: 'POST', body: JSON.stringify(body) }, t),
    update: (id: string, body: ProfileUpdate) =>
      request<RagProfile>(`/api/rag/profiles/${id}/versions`, { method: 'POST', body: JSON.stringify(body) }, t),
    activate: (id: string) =>
      request<Activation>(`/api/rag/profiles/${id}/activate`, { method: 'POST' }, t),
    activation: (activationId: string) =>
      request<Activation>(`/api/rag/profiles/activations/${activationId}`, {}, t),
  },

  query: (body: QueryRequest) =>
    request<RagAnswer>('/api/rag/query', { method: 'POST', body: JSON.stringify(body) }, t),
}

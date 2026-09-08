import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ApiError } from '../api/http'
import {
  rag,
  type Activation,
  type JobState,
  type ProfileCreate,
  type ProfileUpdate,
  type QueryRequest,
  type RagAnswer,
} from '../api/rag'
import { useTenant } from './useTenant'

// ---- queries --------------------------------------------------------------

export function useDocuments() {
  const tenant = useTenant()
  return useQuery({
    queryKey: ['rag', tenant, 'documents'],
    queryFn: () => rag.documents.list(),
  })
}

export function useDocument(id: string | null) {
  const tenant = useTenant()
  return useQuery({
    queryKey: ['rag', tenant, 'document', id],
    queryFn: () => rag.documents.get(id as string),
    enabled: !!id,
  })
}

export function useJobs(state: JobState | 'ALL' = 'ALL') {
  const tenant = useTenant()
  return useQuery({
    queryKey: ['rag', tenant, 'jobs', state],
    queryFn: () => rag.jobs.list(state),
    refetchInterval: (query) => {
      const busy = query.state.data?.content.some((j) => j.state === 'QUEUED' || j.state === 'RUNNING')
      return busy ? 2500 : 15_000
    },
  })
}

export function useProfiles() {
  const tenant = useTenant()
  return useQuery({
    queryKey: ['rag', tenant, 'profiles'],
    queryFn: () => rag.profiles.list(),
  })
}

export function useProfileVersions(id: string | null) {
  const tenant = useTenant()
  return useQuery({
    queryKey: ['rag', tenant, 'profile-versions', id],
    queryFn: () => rag.profiles.versions(id as string),
    enabled: !!id,
  })
}

export function useActivation(activationId: string | null) {
  const tenant = useTenant()
  return useQuery({
    queryKey: ['rag', tenant, 'activation', activationId],
    queryFn: () => rag.profiles.activation(activationId as string),
    enabled: !!activationId,
    refetchInterval: (query) => (query.state.data?.state === 'PENDING' ? 2000 : false),
  })
}

// ---- mutations ----------------------------------------------------------- -

function useRagInvalidator() {
  const qc = useQueryClient()
  const tenant = useTenant()
  return () => qc.invalidateQueries({ queryKey: ['rag', tenant] })
}

export function useUploadDocument() {
  const invalidate = useRagInvalidator()
  return useMutation<
    unknown,
    ApiError,
    { file: File; sourceKey?: string; onProgress?: (f: number) => void }
  >({
    mutationFn: ({ file, sourceKey, onProgress }) => rag.documents.upload(file, { sourceKey, onProgress }),
    onSuccess: invalidate,
  })
}

export function useAddVersion() {
  const invalidate = useRagInvalidator()
  return useMutation<unknown, ApiError, { id: string; file: File; onProgress?: (f: number) => void }>({
    mutationFn: ({ id, file, onProgress }) => rag.documents.addVersion(id, file, onProgress),
    onSuccess: invalidate,
  })
}

export function useActivateVersion() {
  const invalidate = useRagInvalidator()
  return useMutation<unknown, ApiError, { id: string; versionNo: number }>({
    mutationFn: ({ id, versionNo }) => rag.documents.activateVersion(id, versionNo),
    onSuccess: invalidate,
  })
}

export function useReindexVersion() {
  const invalidate = useRagInvalidator()
  return useMutation<unknown, ApiError, { id: string; versionNo: number }>({
    mutationFn: ({ id, versionNo }) => rag.documents.reindexVersion(id, versionNo),
    onSuccess: invalidate,
  })
}

export function useDeleteDocument() {
  const invalidate = useRagInvalidator()
  return useMutation<unknown, ApiError, string>({
    mutationFn: (id) => rag.documents.remove(id),
    onSuccess: invalidate,
  })
}

export function useDeleteVersion() {
  const invalidate = useRagInvalidator()
  return useMutation<unknown, ApiError, { id: string; versionNo: number }>({
    mutationFn: ({ id, versionNo }) => rag.documents.removeVersion(id, versionNo),
    onSuccess: invalidate,
  })
}

export function useCreateProfile() {
  const invalidate = useRagInvalidator()
  return useMutation<unknown, ApiError, ProfileCreate>({
    mutationFn: (body) => rag.profiles.create(body),
    onSuccess: invalidate,
  })
}

export function useUpdateProfile() {
  const invalidate = useRagInvalidator()
  return useMutation<unknown, ApiError, { id: string; body: ProfileUpdate }>({
    mutationFn: ({ id, body }) => rag.profiles.update(id, body),
    onSuccess: invalidate,
  })
}

export function useActivateProfile() {
  const invalidate = useRagInvalidator()
  return useMutation<Activation, ApiError, string>({
    mutationFn: (id) => rag.profiles.activate(id),
    onSuccess: invalidate,
  })
}

export function useRagQuery() {
  return useMutation<RagAnswer, ApiError, QueryRequest>({
    mutationFn: (body) => rag.query(body),
  })
}

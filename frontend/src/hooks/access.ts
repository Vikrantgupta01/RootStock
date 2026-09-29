import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ApiError } from '../api/http'
import { rag, type CreateUser, type CreatedUser } from '../api/rag'
import { useTenantId } from './useTenantId'

/** Groups available to tag documents with. Admin/editor only -- 403 otherwise. */
export function useAccessGroups(enabled = true) {
  const tenant = useTenantId()
  return useQuery({
    queryKey: ['rag', tenant, 'access-groups'],
    queryFn: () => rag.accessGroups.list(),
    enabled,
  })
}

export function useCreateAccessGroup() {
  const qc = useQueryClient()
  const tenant = useTenantId()
  return useMutation<unknown, ApiError, { name: string; description?: string }>({
    mutationFn: ({ name, description }) => rag.accessGroups.create(name, description),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['rag', tenant] }),
  })
}

export function useSetDocumentAccessGroups() {
  const qc = useQueryClient()
  const tenant = useTenantId()
  return useMutation<unknown, ApiError, { documentId: string; groups: string[] }>({
    mutationFn: ({ documentId, groups }) => rag.accessGroups.setForDocument(documentId, groups),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['rag', tenant] }),
  })
}

/** Admin-only. The new user's tenant is the caller's own; there is nothing to pass. */
export function useCreateUser() {
  return useMutation<CreatedUser, ApiError, CreateUser>({
    mutationFn: (body) => rag.users.create(body),
  })
}

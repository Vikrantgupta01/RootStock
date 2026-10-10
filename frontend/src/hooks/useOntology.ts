import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ApiError } from '../api/client'
import {
  ontologyApi,
  type OntologyOverview,
  type OntologyView,
  type PackDetail,
  type ProjectionOutput,
} from '../api/ontology'

export function useOntologyOverview() {
  return useQuery<OntologyOverview, ApiError>({ queryKey: ['ontology', 'overview'], queryFn: ontologyApi.overview })
}

export function useCoreOntology(enabled: boolean) {
  return useQuery<OntologyView, ApiError>({ queryKey: ['ontology', 'core'], queryFn: ontologyApi.core, enabled })
}

export function usePack(name: string | null) {
  return useQuery<PackDetail, ApiError>({
    queryKey: ['ontology', 'pack', name],
    queryFn: () => ontologyApi.pack(name!),
    enabled: name !== null,
  })
}

export function useProjection(pack: string | null, projection: string | null) {
  return useQuery<ProjectionOutput, ApiError>({
    queryKey: ['ontology', 'projection', pack, projection],
    queryFn: () => ontologyApi.projection(pack!, projection!),
    enabled: pack !== null && projection !== null,
  })
}

/** Re-reads the packs from disk; everything shown is refetched. */
export function useReloadPacks() {
  const client = useQueryClient()
  return useMutation<OntologyOverview, ApiError, void>({
    mutationFn: ontologyApi.reload,
    onSuccess: () => client.invalidateQueries({ queryKey: ['ontology'] }),
  })
}

import { useMutation, useQuery } from '@tanstack/react-query'
import type { ApiError } from '../api/client'
import { toolsApi, type ToolCallRequest, type ToolCallResponse, type ToolsOverview } from '../api/tools'
import { useTenantId } from './useTenantId'

/** Nodes, allowlists and tool descriptions; asks the client systems, so not refetched on focus. */
export function useToolsOverview() {
  const tenant = useTenantId()
  return useQuery<ToolsOverview, ApiError>({
    queryKey: ['tools', tenant, 'overview'],
    queryFn: toolsApi.overview,
    refetchOnWindowFocus: false,
  })
}

export function useToolCall() {
  return useMutation<ToolCallResponse, ApiError, ToolCallRequest>({
    mutationFn: toolsApi.call,
  })
}

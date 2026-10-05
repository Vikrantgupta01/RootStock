import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { agentApi, type AgentResponse } from '../api/agent'
import type { ApiError } from '../api/client'
import type { ConversationMessage } from '../api/conversation'
import { useTenantId } from './useTenantId'

export function useAgent() {
  return useMutation<AgentResponse, ApiError, { message: string; conversationId?: string | null }>({
    mutationFn: ({ message, conversationId }) => agentApi.ask(message, conversationId),
  })
}

/** The stored transcript of a thread -- questions and answers only, not the steps. */
export function useAgentTranscript(conversationId: string | null) {
  const tenant = useTenantId()
  return useQuery<ConversationMessage[]>({
    queryKey: ['agent', tenant, 'transcript', conversationId],
    queryFn: () => agentApi.conversations.transcript(conversationId as string),
    enabled: !!conversationId,
  })
}

export function useDeleteAgentConversation() {
  const qc = useQueryClient()
  const tenant = useTenantId()
  return useMutation<void, ApiError, string>({
    mutationFn: (id) => agentApi.conversations.remove(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['agent', tenant] }),
  })
}

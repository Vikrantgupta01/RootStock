import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type ChatResponse } from '../api/client'
import type { ApiError } from '../api/client'
import type { ConversationMessage } from '../api/conversation'
import { useTenantId } from './useTenantId'

export function useChat() {
  return useMutation<ChatResponse, ApiError, { message: string; conversationId?: string | null }>({
    mutationFn: ({ message, conversationId }) => api.chat(message, conversationId),
  })
}

/** The stored transcript of a thread -- what the log shows after a reload. */
export function useChatTranscript(conversationId: string | null) {
  const tenant = useTenantId()
  return useQuery<ConversationMessage[]>({
    queryKey: ['chat', tenant, 'transcript', conversationId],
    queryFn: () => api.conversations.transcript(conversationId as string),
    enabled: !!conversationId,
  })
}

export function useDeleteChatConversation() {
  const qc = useQueryClient()
  const tenant = useTenantId()
  return useMutation<void, ApiError, string>({
    mutationFn: (id) => api.conversations.remove(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['chat', tenant] }),
  })
}

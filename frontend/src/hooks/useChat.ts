import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type ChatResponse } from '../api/client'
import type { ApiError } from '../api/client'
import type { ConversationMessage, ConversationSummary } from '../api/conversation'
import { useTenantId } from './useTenantId'

export function useChat() {
  const qc = useQueryClient()
  const tenant = useTenantId()
  return useMutation<ChatResponse, ApiError, { message: string; conversationId?: string | null }>({
    mutationFn: ({ message, conversationId }) => api.chat(message, conversationId),
    // A new message renames nothing but does reorder the list, and a first
    // message creates a thread that isn't in it yet.
    onSuccess: () => qc.invalidateQueries({ queryKey: ['chat', tenant, 'conversations'] }),
  })
}

export function useChatConversations() {
  const tenant = useTenantId()
  return useQuery<ConversationSummary[]>({
    queryKey: ['chat', tenant, 'conversations'],
    queryFn: () => api.conversations.list(),
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

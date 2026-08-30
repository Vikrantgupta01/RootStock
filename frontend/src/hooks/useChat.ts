import { useMutation } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ApiError } from '../api/client'

export function useChat() {
  return useMutation<{ reply: string }, ApiError, string>({
    mutationFn: (message: string) => api.chat(message),
  })
}

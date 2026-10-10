import { useQuery } from '@tanstack/react-query'
import type { ApiError } from '../api/client'
import { statusApi, type PlatformStatus } from '../api/status'

/** Each check makes real calls (one of them a small Bedrock request), so only on demand. */
export function usePlatformStatus() {
  return useQuery<PlatformStatus, ApiError>({
    queryKey: ['status'],
    queryFn: statusApi.check,
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  })
}

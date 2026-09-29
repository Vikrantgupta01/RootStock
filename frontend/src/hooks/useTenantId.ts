import { useCurrentUser } from './useSession'

/**
 * The signed-in user's tenant, used to scope cached queries so switching
 * accounts can never show the previous one's documents from cache.
 */
export function useTenantId(): string {
  return useCurrentUser().data?.tenantId ?? 'anonymous'
}

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useSyncExternalStore } from 'react'
import { auth, type CurrentUser, type Role } from '../api/auth'
import { getSession, onSessionChange } from '../api/session'

/** Whether a token is stored at all, re-rendering on sign-in and sign-out. */
export function useHasSession(): boolean {
  return useSyncExternalStore(
    (cb) => onSessionChange(cb),
    () => getSession() !== null,
    () => false,
  )
}

/**
 * The signed-in user as the backend reads them off the token. Everything the UI
 * gates on -- tenant, role, groups -- comes from here rather than from anything
 * the client decided for itself.
 */
export function useCurrentUser() {
  const hasSession = useHasSession()
  return useQuery<CurrentUser>({
    queryKey: ['auth', 'me'],
    queryFn: () => auth.me(),
    enabled: hasSession,
    staleTime: 5 * 60 * 1000,
    retry: false,
  })
}

export function useSignOut(): () => void {
  const qc = useQueryClient()
  return useCallback(() => {
    auth.logout()
    qc.clear()
  }, [qc])
}

export function hasRole(user: CurrentUser | undefined, ...roles: Role[]): boolean {
  return user !== undefined && roles.includes(user.role)
}

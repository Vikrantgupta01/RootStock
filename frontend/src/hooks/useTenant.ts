import { useSyncExternalStore } from 'react'
import { getTenant, onTenantChange } from '../api/tenant'

/** Current tenant id, re-rendering when it changes. */
export function useTenant(): string {
  return useSyncExternalStore(
    (cb) => onTenantChange(cb),
    getTenant,
    () => 'default',
  )
}

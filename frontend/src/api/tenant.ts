// The backend scopes every RAG resource by the `X-Tenant-Id` header (a stub for
// real auth). We keep the chosen tenant in localStorage and notify listeners so
// the UI re-renders when it changes.

const KEY = 'rootstock.tenant'
const DEFAULT = 'default'
const EVENT = 'rootstock:tenant-changed'

export function getTenant(): string {
  try {
    return localStorage.getItem(KEY) || DEFAULT
  } catch {
    return DEFAULT
  }
}

export function setTenant(value: string): void {
  const next = value.trim() || DEFAULT
  try {
    localStorage.setItem(KEY, next)
  } catch {
    // ignore: storage may be unavailable
  }
  window.dispatchEvent(new CustomEvent(EVENT))
}

export function onTenantChange(listener: () => void): () => void {
  window.addEventListener(EVENT, listener)
  return () => window.removeEventListener(EVENT, listener)
}

// Which thread this browser was last in, per surface. The history itself lives
// on the server; this is only a per-browser convenience, so storage being
// unavailable (private mode, blocked site data) just means starting fresh.

export function rememberedConversation(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export function rememberConversation(key: string, id: string | null) {
  try {
    if (id) localStorage.setItem(key, id)
    else localStorage.removeItem(key)
  } catch {
    // ignore: storage may be unavailable
  }
}

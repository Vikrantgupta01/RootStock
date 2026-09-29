// Sign-in and identity. Login goes through the backend, which calls Cognito --
// this client never talks to AWS directly and holds no AWS credentials.

import { request } from './http'
import { clearSession, setSession } from './session'

export type Role = 'ADMIN' | 'EDITOR' | 'VIEWER'

export interface CurrentUser {
  userId: string
  email: string | null
  tenantId: string
  role: Role
  /** Cognito group memberships; these decide which documents queries may retrieve. */
  groups: string[]
}

interface TokenResponse {
  idToken: string
  refreshToken: string | null
  expiresInSeconds: number
}

export const auth = {
  async login(email: string, password: string): Promise<void> {
    const body = await request<TokenResponse>(
      '/api/auth/login',
      { method: 'POST', body: JSON.stringify({ email, password }) },
      { anonymous: true },
    )
    setSession({
      idToken: body.idToken,
      refreshToken: body.refreshToken,
      expiresAt: Date.now() + body.expiresInSeconds * 1000,
    })
  },

  me: () => request<CurrentUser>('/api/auth/me'),

  logout(): void {
    clearSession()
  },
}

import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, useSyncExternalStore } from 'react'
import { api, getSession, onSessionChange, type ApiError } from './api'
import { Login } from './Login'
import { Queue } from './Queue'
import { Review } from './Review'

export function App() {
  const session = useSyncExternalStore(onSessionChange, getSession, () => null)
  const [open, setOpen] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const client = useQueryClient()
  const me = useQuery({ queryKey: ['me', session?.idToken], queryFn: api.me, enabled: session !== null })

  if (!session) return <Login />

  const coordinator = me.data && (me.data.groups.includes('coordinator') || me.data.role === 'ADMIN')
  return (
    <div className="app">
      <header className="top">
        <div>
          <strong>Vinnies</strong> · Case review
          <span className="tag">fictional demo data</span>
        </div>
        <div className="who">
          {me.data?.email}
          <button className="link" onClick={() => { api.logout(); client.clear() }}>Sign out</button>
        </div>
      </header>
      <main>
        {notice && (
          <div className="notice" onClick={() => setNotice(null)}>
            {notice}
          </div>
        )}
        {me.isError && <div className="error">{(me.error as unknown as ApiError).detail}</div>}
        {me.data && !coordinator && (
          <p className="muted">
            Only coordinators review cases. You are signed in as {me.data.email}, who is not one.
          </p>
        )}
        {coordinator &&
          (open ? (
            <Review
              caseId={open}
              onDone={(message) => {
                setNotice(message)
                setOpen(null)
                client.invalidateQueries({ queryKey: ['awaiting'] })
              }}
              onBack={() => setOpen(null)}
            />
          ) : (
            <Queue onOpen={setOpen} />
          ))}
      </main>
    </div>
  )
}

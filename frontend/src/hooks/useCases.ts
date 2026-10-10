import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { ApiError } from '../api/client'
import { casesApi, isRunEnd, type CaseGraph, type CaseRun, type RunEvent, type SubmitCase } from '../api/cases'

export function useCaseGraph() {
  return useQuery<CaseGraph, ApiError>({ queryKey: ['cases', 'graph'], queryFn: casesApi.graph, staleTime: Infinity })
}

export function useRecentCases() {
  return useQuery<CaseRun[], ApiError>({ queryKey: ['cases', 'recent'], queryFn: casesApi.recent })
}

export function useCaseDetail(caseId: string | null) {
  return useQuery<CaseRun, ApiError>({
    queryKey: ['cases', 'detail', caseId],
    queryFn: () => casesApi.detail(caseId!),
    enabled: caseId !== null,
  })
}

export function useSubmitCase() {
  const client = useQueryClient()
  return useMutation<CaseRun, ApiError, SubmitCase>({
    mutationFn: casesApi.submit,
    onSuccess: () => client.invalidateQueries({ queryKey: ['cases', 'recent'] }),
  })
}

/**
 * Follows a case's run live: every event so far, then each new one. When the
 * run pauses or ends, the case's detail and the recent list are refetched so
 * the result appears.
 */
export function useRunEvents(caseId: string | null) {
  const client = useQueryClient()
  // Keyed by case, so switching cases shows nothing stale without resetting state in the effect.
  const [stream, setStream] = useState<{ caseId: string | null; events: RunEvent[]; error: string | null }>({
    caseId: null,
    events: [],
    error: null,
  })

  useEffect(() => {
    if (!caseId) return
    const controller = new AbortController()
    casesApi
      .events(
        caseId,
        (event) => {
          setStream((s) => {
            const events = s.caseId === caseId ? s.events : []
            return events.some((e) => e.seq === event.seq)
              ? s
              : { caseId, events: [...events, event], error: null }
          })
          if (isRunEnd(event)) {
            client.invalidateQueries({ queryKey: ['cases', 'detail', caseId] })
            client.invalidateQueries({ queryKey: ['cases', 'recent'] })
          }
        },
        controller.signal,
      )
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setStream((s) => ({ ...s, caseId, error: e instanceof Error ? e.message : ((e as Partial<ApiError>)?.detail ?? 'The progress stream stopped') }))
        }
      })
    return () => controller.abort()
  }, [caseId, client])

  const current = stream.caseId === caseId
  return { events: current ? stream.events : [], error: current ? stream.error : null }
}

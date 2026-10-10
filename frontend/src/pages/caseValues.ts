import { usePack } from '../hooks/useOntology'

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json }

/** visitDate → Visit date */
export function label(key: string) {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** household.suburb → Household › suburb */
export function pathLabel(path: string) {
  return path.split('.').map((part, i) => (i === 0 ? label(part) : label(part).toLowerCase())).join(' › ')
}

/** Each vocabulary code of the pack's ontology with its definition, for hover text. */
export function useDefinitions(pack: string) {
  const detail = usePack(pack)
  const definitions = new Map<string, string>()
  for (const v of detail.data?.ontology?.vocabularies ?? []) {
    for (const t of v.terms) {
      if (t.definition && !definitions.has(t.code)) definitions.set(t.code, `${v.name}: ${t.definition}`)
    }
  }
  return definitions
}

/** An issue as the run's result holds it (a core.Issue). */
export interface CaseIssueView {
  ruleId: string
  severity: 'BLOCKING' | 'WARNING'
  answerableBy: 'SUBMITTER' | 'EXTERNAL' | 'REVIEWER'
  message: string
  /** The record field it is about; null for the whole case. */
  path: string | null
  layer: 'STRUCTURAL' | 'SEMANTIC' | 'BUSINESS' | 'JUDGMENT' | null
}

export function caseIssues(result: Record<string, unknown> | null): CaseIssueView[] {
  return Array.isArray(result?.issues) ? (result.issues as CaseIssueView[]) : []
}

/** The ids validate gives a required field the input did not have. */
export const REQUIRED_FIELD = 'required-field'

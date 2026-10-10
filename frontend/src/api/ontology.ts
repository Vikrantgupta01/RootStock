// Ontology explorer: the core ontology, the domain packs Rootstock found, and
// what is generated from each pack (projection schemas and prompt glossaries).

import { request } from './http'

export type PackStatus = 'VALID' | 'INVALID' | 'NO_ONTOLOGY'

export interface OntologyProblem {
  /** Where: a path such as entities.Case.attributes.urgency, or a line number. */
  at: string
  message: string
}

export interface OntologySummary {
  name: string
  version: string | null
  description: string | null
  concepts: number
  vocabularies: number
  projections: number
}

export interface PackSummary {
  name: string
  location: string
  files: string[]
  status: PackStatus
  ontology: OntologySummary | null
  problems: OntologyProblem[]
}

export interface OntologyOverview {
  paths: string[]
  pathProblems: string[]
  loadedAt: string
  core: OntologySummary
  packs: PackSummary[]
}

export interface AttributeView {
  name: string
  type: string | null
  vocab: string | null
  required: boolean
  many: boolean
  min: number | null
  max: number | null
  pii: boolean
  description: string | null
  /** The concept that declares it: this one, or one it extends. */
  declaredBy: string
}

export interface RelationView {
  name: string
  to: string
  field: string
  required: boolean
  many: boolean
  min: number | null
  max: number | null
  description: string | null
  declaredBy: string
}

export interface EntityView {
  name: string
  extendsRef: string | null
  ancestors: string[]
  description: string | null
  attributes: AttributeView[]
  relations: RelationView[]
}

export interface Term {
  code: string
  definition: string | null
  synonyms: string[]
}

export interface Vocabulary {
  name: string
  origin: string
  description: string | null
  terms: Term[]
}

export interface Constraint {
  id: string
  description: string | null
  when: Record<string, unknown>
  require: Record<string, unknown>
}

export interface Projection {
  name: string
  root: string
  embed: string[]
  reference: string[]
  include: string[]
  description: string | null
}

export interface OntologyView {
  name: string
  version: string | null
  extendsName: string | null
  description: string | null
  entities: EntityView[]
  vocabularies: Vocabulary[]
  constraints: Constraint[]
  projections: Projection[]
}

export interface PackDetail {
  pack: PackSummary
  /** Null unless the pack's ontology is valid. */
  ontology: OntologyView | null
}

export interface ProjectionOutput {
  pack: string
  projection: string
  schema: Record<string, unknown>
  glossary: string
}

export const ontologyApi = {
  overview: () => request<OntologyOverview>('/api/ontology'),
  core: () => request<OntologyView>('/api/ontology/core'),
  pack: (name: string) => request<PackDetail>(`/api/ontology/packs/${encodeURIComponent(name)}`),
  projection: (pack: string, projection: string) =>
    request<ProjectionOutput>(
      `/api/ontology/packs/${encodeURIComponent(pack)}/projections/${encodeURIComponent(projection)}`,
    ),
  reload: () => request<OntologyOverview>('/api/ontology/reload', { method: 'POST' }),
}

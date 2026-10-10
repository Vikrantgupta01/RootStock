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

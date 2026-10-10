import type { Json, JsonSchema } from './api'
import { codeLabel, fieldLabel } from './format'

type Obj = Record<string, Json>

interface Props {
  schema: JsonSchema
  value: Obj
  onChange: (value: Obj) => void
  /** Field path → what validation said about it. */
  flagged: Map<string, string>
}

/**
 * The case record as a form, built from the ontology's schema for it: a choice
 * for each vocabulary, a date picker for dates, a list for each list, a group
 * for each embedded part. Nothing about Vinnies' fields is written here; a
 * change to the ontology changes the form.
 */
export function RecordForm({ schema, value, onChange, flagged }: Props) {
  return <ObjectFields schema={schema} defs={schema.$defs ?? {}} value={value} onChange={onChange} path="" flagged={flagged} />
}

interface FieldProps {
  defs: Record<string, JsonSchema>
  path: string
  flagged: Map<string, string>
}

function resolve(s: JsonSchema, defs: Record<string, JsonSchema>): JsonSchema {
  if (s.$ref) return defs[s.$ref.split('/').pop()!] ?? {}
  const ref = s.anyOf?.find((a) => a.$ref)
  return ref ? { ...defs[ref.$ref!.split('/').pop()!], description: s.description } : s
}

function types(s: JsonSchema): string[] {
  return Array.isArray(s.type) ? s.type : s.type ? [s.type] : []
}

function ObjectFields({ schema, defs, value, onChange, path, flagged }: FieldProps & {
  schema: JsonSchema
  value: Obj
  onChange: (v: Obj) => void
}) {
  return (
    <div className="fields">
      {Object.entries(schema.properties ?? {}).map(([key, prop]) => {
        const at = path ? `${path}.${key}` : key
        return (
          <Field
            key={key}
            name={key}
            schema={prop}
            defs={defs}
            path={at}
            flagged={flagged}
            value={value[key] ?? null}
            onChange={(v) => onChange({ ...value, [key]: v })}
          />
        )
      })}
    </div>
  )
}

function Field({ name, schema, defs, path, flagged, value, onChange }: FieldProps & {
  name: string
  schema: JsonSchema
  value: Json
  onChange: (v: Json) => void
}) {
  const s = resolve(schema, defs)
  const kinds = types(s)
  const note = flagged.get(path)
  const label = (
    <span className={note ? 'label flagged' : 'label'} title={note ?? s.description}>
      {fieldLabel(name)}
    </span>
  )

  if (kinds.includes('object') || s.properties) {
    if (value === null) {
      return (
        <div className="field">
          {label}
          <button type="button" className="link" onClick={() => onChange({})}>Add {fieldLabel(name).toLowerCase()}</button>
        </div>
      )
    }
    return (
      <fieldset className="group">
        <legend>{label}</legend>
        <ObjectFields schema={s} defs={defs} value={value as Obj} onChange={onChange} path={path} flagged={flagged} />
      </fieldset>
    )
  }

  if (kinds.includes('array')) {
    const items = Array.isArray(value) ? value : []
    const item = resolve(s.items ?? {}, defs)
    if (item.enum) {
      const codes = item.enum.filter((c): c is string => c !== null)
      return (
        <div className="field">
          {label}
          <div className="checks">
            {codes.map((code) => (
              <label key={code} className="check">
                <input
                  type="checkbox"
                  checked={items.includes(code)}
                  onChange={(e) => onChange(e.target.checked ? [...items, code] : items.filter((c) => c !== code))}
                />
                {codeLabel(code)}
              </label>
            ))}
          </div>
        </div>
      )
    }
    return (
      <fieldset className="group">
        <legend>{label}</legend>
        {items.map((it, i) => (
          <div key={i} className="item">
            <ObjectFields
              schema={item}
              defs={defs}
              value={(it ?? {}) as Obj}
              path={`${path}[${i}]`}
              flagged={flagged}
              onChange={(v) => onChange(items.map((x, j) => (j === i ? v : x)))}
            />
            <button type="button" className="link danger" onClick={() => onChange(items.filter((_, j) => j !== i))}>
              Remove
            </button>
          </div>
        ))}
        <button type="button" className="link" onClick={() => onChange([...items, {}])}>
          Add {(item.title ?? fieldLabel(name)).toLowerCase()}
        </button>
      </fieldset>
    )
  }

  let input
  if (s.enum) {
    input = (
      <select value={value === null ? '' : String(value)} onChange={(e) => onChange(e.target.value || null)}>
        <option value="">— not given —</option>
        {s.enum.filter((c): c is string => c !== null).map((c) => (
          <option key={c} value={c}>{codeLabel(c)}</option>
        ))}
      </select>
    )
  }
  else if (kinds.includes('boolean')) {
    input = (
      <select value={value === null ? '' : String(value)} onChange={(e) => onChange(e.target.value === '' ? null : e.target.value === 'true')}>
        <option value="">— not given —</option>
        <option value="true">Yes</option>
        <option value="false">No</option>
      </select>
    )
  }
  else if (kinds.includes('number') || kinds.includes('integer')) {
    input = (
      <input
        type="number"
        step={kinds.includes('integer') ? 1 : 'any'}
        value={value === null ? '' : String(value)}
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
      />
    )
  }
  else if (s.format === 'date') {
    input = <input type="date" value={value === null ? '' : String(value)} onChange={(e) => onChange(e.target.value || null)} />
  }
  else {
    input = <input type="text" value={value === null ? '' : String(value)} onChange={(e) => onChange(e.target.value || null)} />
  }
  return (
    <label className="field">
      {label}
      {input}
    </label>
  )
}

import type { CaseRun } from '../api/cases'
import { caseIssues, pathLabel, type CaseIssueView } from './caseValues'

const WHO: Record<CaseIssueView['answerableBy'], string> = {
  SUBMITTER: 'the member will be asked',
  EXTERNAL: 'the household will be asked',
  REVIEWER: 'the coordinator decides',
}

const LAYER: Record<NonNullable<CaseIssueView['layer']>, string> = {
  STRUCTURAL: 'record',
  SEMANTIC: 'ontology',
  BUSINESS: 'rule',
  JUDGMENT: 'judge',
}

/**
 * What validate found, blocking first: each issue's severity, what it says, the
 * field it is about, which check found it, and who can resolve it. A blocking
 * issue keeps the case from draft until it is resolved.
 */
export function CaseIssues({ run }: { run: CaseRun }) {
  const issues = caseIssues(run.result)
  const blocking = issues.filter((i) => i.severity === 'BLOCKING')
  const warnings = issues.filter((i) => i.severity === 'WARNING')
  return (
    <section className="issues">
      <h3>
        Issues{' '}
        <span className="muted issues__count">
          {issues.length === 0
            ? 'none: every check passed'
            : `${blocking.length} blocking, ${warnings.length} warning${warnings.length === 1 ? '' : 's'}`}
        </span>
      </h3>
      {issues.length > 0 && (
        <ul className="issues__list">
          {[...blocking, ...warnings].map((i, n) => (
            <li key={n} className={`issues__item issues__item--${i.severity.toLowerCase()}`}>
              <span className={`badge ${i.severity === 'BLOCKING' ? 'badge--error' : 'badge--busy'}`}>
                {i.severity.toLowerCase()}
              </span>
              <div className="issues__body">
                <p className="issues__message">{i.message}</p>
                <p className="muted issues__meta">
                  {i.path && <>{pathLabel(i.path)} · </>}
                  {i.layer ? `${LAYER[i.layer]} ` : ''}
                  <code>{i.ruleId}</code> · {WHO[i.answerableBy]}
                </p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

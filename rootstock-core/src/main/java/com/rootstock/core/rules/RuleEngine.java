package com.rootstock.core.rules;

import com.rootstock.core.graph.CaseIssue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a pack's rules from their specs and runs them. A rule that fails while
 * running does not fail the case: it becomes a WARNING for the reviewer, saying
 * which rule could not run, so a check is never silently skipped.
 */
public final class RuleEngine {

	private final List<Rule> rules;

	public RuleEngine(List<Rule> rules) {
		this.rules = List.copyOf(rules);
	}

	/**
	 * @throws IllegalArgumentException listing every spec that cannot be built (unknown kind, bad settings)
	 */
	public static RuleEngine build(List<RuleSpec> specs, Map<String, RuleKind> kinds) {
		List<String> problems = problems(specs, kinds);
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException(String.join("; ", problems));
		}
		return new RuleEngine(specs.stream().map(s -> kinds.get(s.kind()).create(s)).toList());
	}

	/** What is wrong with each spec, for the startup check; empty when all of them build. */
	public static List<String> problems(List<RuleSpec> specs, Map<String, RuleKind> kinds) {
		List<String> problems = new ArrayList<>();
		Map<String, Boolean> seen = new LinkedHashMap<>();
		for (RuleSpec s : specs) {
			if (seen.put(s.id(), Boolean.TRUE) != null) {
				problems.add("rule id '" + s.id() + "' is used twice");
			}
			RuleKind kind = kinds.get(s.kind());
			if (kind == null) {
				problems.add("rule '" + s.id() + "': unknown kind '" + s.kind() + "'; known: "
						+ kinds.keySet().stream().sorted().toList());
				continue;
			}
			try {
				kind.create(s);
			}
			catch (IllegalArgumentException e) {
				problems.add(e.getMessage());
			}
		}
		return problems;
	}

	public List<Rule> rules() {
		return rules;
	}

	public List<CaseIssue> evaluate(RuleContext context) {
		List<CaseIssue> issues = new ArrayList<>();
		for (Rule rule : rules) {
			try {
				issues.addAll(rule.evaluate(context));
			}
			catch (RuntimeException e) {
				issues.add(new CaseIssue(rule.id(), CaseIssue.WARNING, CaseIssue.REVIEWER, "Rule " + rule.id()
						+ " could not run: " + e.getMessage(), null, CaseIssue.BUSINESS));
			}
		}
		return issues;
	}
}

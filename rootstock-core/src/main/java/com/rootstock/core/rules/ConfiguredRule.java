package com.rootstock.core.rules;

import com.rootstock.core.graph.CaseIssue;
import java.util.Map;

/** The parts every configured rule shares: its spec, and how it reports a breach. */
public abstract class ConfiguredRule implements Rule {

	protected final RuleSpec spec;

	protected ConfiguredRule(RuleSpec spec) {
		this.spec = spec;
	}

	@Override
	public String id() {
		return spec.id();
	}

	protected CaseIssue issue(String defaultMessage, Map<String, ?> values, String path) {
		String answerableBy = spec.answerableBy() != null ? spec.answerableBy()
				: CaseIssue.BLOCKING.equals(spec.severity()) ? CaseIssue.SUBMITTER : CaseIssue.REVIEWER;
		String message = Values.fill(spec.message() != null ? spec.message() : defaultMessage, values);
		return new CaseIssue(spec.id(), spec.severity(), answerableBy, message, path, CaseIssue.BUSINESS);
	}

	/** A setting the kind cannot do without. */
	protected static Object required(RuleSpec spec, String key) {
		Object value = spec.config().get(key);
		if (value == null) {
			throw new IllegalArgumentException("rule '" + spec.id() + "' (" + spec.kind() + ") needs '" + key + "'");
		}
		return value;
	}

	protected static String requiredText(RuleSpec spec, String key) {
		Object value = required(spec, key);
		if (!(value instanceof String s) || s.isBlank()) {
			throw new IllegalArgumentException("rule '" + spec.id() + "': '" + key + "' should be text");
		}
		return s;
	}

	protected static String statePath(RuleSpec spec, String key) {
		String path = requiredText(spec, key);
		if (!path.startsWith("$.")) {
			throw new IllegalArgumentException("rule '" + spec.id() + "': '" + key + "' should be a state path such as "
					+ "$.record.needs, not '" + path + "'");
		}
		return path;
	}
}

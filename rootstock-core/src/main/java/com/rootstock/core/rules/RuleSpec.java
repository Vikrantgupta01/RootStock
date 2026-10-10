package com.rootstock.core.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One rule as written in a pack's {@code rules.yaml}: which kind of rule, how
 * serious a breach is, who can resolve it, and the kind's own settings.
 *
 * @param answerableBy who can resolve a breach; null for the default (SUBMITTER when blocking, else REVIEWER)
 * @param message      what a breach says, with {@code {placeholders}} the kind fills in; null for the kind's own
 * @param config       everything else in the rule's entry, for its kind to read
 */
public record RuleSpec(String id, String kind, String severity, String answerableBy, String description,
		String message, Map<String, Object> config) {

	public RuleSpec {
		config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
	}
}

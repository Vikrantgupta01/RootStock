package com.rootstock.core.graph;

import java.io.Serializable;
import java.util.List;

/**
 * A problem found while processing a case (a core.Issue).
 *
 * <p>Severity says whether the case can go on: a {@link #BLOCKING} issue stops it
 * reaching draft until someone resolves it; a {@link #WARNING} goes with the case
 * to review. Who can resolve it decides the route: the {@link #SUBMITTER} is
 * asked (clarify), an {@link #EXTERNAL} party is chased (chase), and the
 * {@link #REVIEWER} decides at review.
 *
 * @param ruleId       what found it: a rule's id, a constraint's id, {@code required-field}, or a judge agent's name
 * @param path         the field it is about, e.g. {@code visitDate}; null when it is about the whole case
 * @param layer        which validation layer found it: {@link #STRUCTURAL}, {@link #SEMANTIC}, {@link #BUSINESS}
 *                     or {@link #JUDGMENT}; null when none (e.g. a stub's simulated issue)
 */
public record CaseIssue(String ruleId, String severity, String answerableBy, String message, String path, String layer)
		implements Serializable {

	public static final String BLOCKING = "BLOCKING";
	public static final String WARNING = "WARNING";
	public static final List<String> SEVERITIES = List.of(BLOCKING, WARNING);

	public static final String SUBMITTER = "SUBMITTER";
	public static final String EXTERNAL = "EXTERNAL";
	public static final String REVIEWER = "REVIEWER";
	public static final List<String> ANSWERABLE_BY = List.of(SUBMITTER, EXTERNAL, REVIEWER);

	/** The record's shape: types, allowed codes, required fields. */
	public static final String STRUCTURAL = "STRUCTURAL";
	/** The ontology's constraints across fields. */
	public static final String SEMANTIC = "SEMANTIC";
	/** The pack's rules: limits, frequency, consent. */
	public static final String BUSINESS = "BUSINESS";
	/** A judge agent: is the record consistent with the input? */
	public static final String JUDGMENT = "JUDGMENT";
	public static final List<String> LAYERS = List.of(STRUCTURAL, SEMANTIC, BUSINESS, JUDGMENT);

	public CaseIssue {
		if (!SEVERITIES.contains(severity)) {
			throw new IllegalArgumentException("Unknown severity '" + severity + "'; one of " + SEVERITIES);
		}
		if (!ANSWERABLE_BY.contains(answerableBy)) {
			throw new IllegalArgumentException("Unknown answerableBy '" + answerableBy + "'; one of " + ANSWERABLE_BY);
		}
		if (layer != null && !LAYERS.contains(layer)) {
			throw new IllegalArgumentException("Unknown layer '" + layer + "'; one of " + LAYERS);
		}
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("An issue needs a message a person can read");
		}
	}

	public CaseIssue(String ruleId, String severity, String answerableBy, String message, String path) {
		this(ruleId, severity, answerableBy, message, path, null);
	}

	public CaseIssue(String ruleId, String severity, String answerableBy, String message) {
		this(ruleId, severity, answerableBy, message, null, null);
	}

	public boolean blocking() {
		return BLOCKING.equals(severity);
	}
}

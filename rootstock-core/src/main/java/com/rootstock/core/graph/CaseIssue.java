package com.rootstock.core.graph;

import java.io.Serializable;

/**
 * A problem found while processing a case (a core.Issue).
 *
 * @param severity     {@link #BLOCKING} or {@link #WARNING}
 * @param answerableBy who can resolve it: {@link #SUBMITTER} (route to clarify) or {@link #EXTERNAL} (route to chase)
 * @param path         the field it is about, e.g. {@code visitDate}; null when it is about the whole case
 */
public record CaseIssue(String ruleId, String severity, String answerableBy, String message, String path)
		implements Serializable {

	public static final String BLOCKING = "BLOCKING";
	public static final String WARNING = "WARNING";
	public static final String SUBMITTER = "SUBMITTER";
	public static final String EXTERNAL = "EXTERNAL";

	public CaseIssue(String ruleId, String severity, String answerableBy, String message) {
		this(ruleId, severity, answerableBy, message, null);
	}
}

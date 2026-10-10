package com.rootstock.core.ontology;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A rule across fields: when every {@code when} condition holds, every
 * {@code require} condition must hold too. Keys are paths such as
 * {@code Case.riskFlags}; values are {@code notEmpty}, {@code empty}, a code, a
 * list of codes, or a literal.
 */
public record Constraint(String id, String description, Map<String, Object> when, Map<String, Object> require) {

	public static final String NOT_EMPTY = "notEmpty";
	public static final String EMPTY = "empty";

	public Constraint {
		when = Collections.unmodifiableMap(new LinkedHashMap<>(when));
		require = Collections.unmodifiableMap(new LinkedHashMap<>(require));
	}
}

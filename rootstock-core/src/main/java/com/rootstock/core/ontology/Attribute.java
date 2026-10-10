package com.rootstock.core.ontology;

import java.math.BigDecimal;
import java.util.List;

/**
 * A value an entity carries. Exactly one of {@code type} and {@code vocab} is set.
 *
 * @param type  one of {@link #TYPES}
 * @param vocab a vocabulary name; values must be its codes
 * @param pii   personal information: masked in traces and logs, kept out of projections that don't need it
 */
public record Attribute(String name, String type, String vocab, boolean required, boolean many, BigDecimal min,
		BigDecimal max, boolean pii, String description) {

	public static final List<String> TYPES = List.of("string", "boolean", "integer", "decimal",
			"date", "datetime");

	public boolean numeric() {
		return "integer".equals(type) || "decimal".equals(type);
	}
}

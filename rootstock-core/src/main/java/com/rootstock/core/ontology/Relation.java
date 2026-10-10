package com.rootstock.core.ontology;

/**
 * A link from one entity to another, such as Case concerns Household.
 *
 * @param to    target entity: a name in the same ontology, or {@code core.Name}
 * @param field the property name in generated schemas; defaults to the relation name
 * @param min   with {@code many}: the fewest allowed
 * @param max   with {@code many}: the most allowed
 */
public record Relation(String name, String to, String field, boolean required, boolean many, Integer min, Integer max,
		String description) {

	public String fieldOrName() {
		return field == null || field.isBlank() ? name : field;
	}
}

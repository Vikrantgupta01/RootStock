package com.rootstock.core.ontology;

import java.util.List;

/**
 * A concept, such as a Case or a Household.
 *
 * @param origin     the name of the ontology that declares it
 * @param extendsRef the concept it specialises, e.g. {@code core.Case}; null when none
 */
public record Entity(String name, String origin, String extendsRef, String description, List<Attribute> attributes,
		List<Relation> relations) {

	public Entity {
		attributes = List.copyOf(attributes);
		relations = List.copyOf(relations);
	}
}

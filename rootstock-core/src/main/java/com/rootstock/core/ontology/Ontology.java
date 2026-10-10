package com.rootstock.core.ontology;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One ontology file as written: the core ontology, or a domain pack's
 * {@code ontology.yaml} that extends it. References between them are resolved by
 * {@link ResolvedOntology}; whether they make sense is checked by
 * {@link OntologyValidator}.
 *
 * @param extendsName the ontology this one builds on ({@code rootstock-core}), or null for the core itself
 */
public record Ontology(String name, String version, String extendsName, String description,
		Map<String, Entity> entities, Map<String, Vocabulary> vocabularies, List<Constraint> constraints,
		Map<String, Projection> projections) {

	public Ontology {
		// Ordered copies: declaration order is the order in schemas and on screen.
		entities = Collections.unmodifiableMap(new LinkedHashMap<>(entities));
		vocabularies = Collections.unmodifiableMap(new LinkedHashMap<>(vocabularies));
		constraints = List.copyOf(constraints);
		projections = Collections.unmodifiableMap(new LinkedHashMap<>(projections));
	}
}

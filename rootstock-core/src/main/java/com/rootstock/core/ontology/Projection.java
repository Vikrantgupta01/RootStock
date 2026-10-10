package com.rootstock.core.ontology;

import java.util.List;

/**
 * A view of the ontology rooted at one entity, from which a JSON Schema is
 * generated. Related entities are either embedded (filled in from the input) or
 * referenced by id (already in the client system); others are left out.
 *
 * @param include when not empty, only these fields of the root (and {@code field.sub} of embedded ones);
 *                referenced entities are always kept
 */
public record Projection(String name, String root, List<String> embed, List<String> reference, List<String> include,
		String description) {

	public Projection {
		embed = List.copyOf(embed);
		reference = List.copyOf(reference);
		include = List.copyOf(include);
	}
}

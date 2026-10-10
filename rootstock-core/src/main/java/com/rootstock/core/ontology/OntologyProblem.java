package com.rootstock.core.ontology;

/**
 * Something wrong with an ontology, said so a person can fix it.
 *
 * @param at where: a path such as {@code entities.Case.attributes.urgency}, or {@code line 12}
 */
public record OntologyProblem(String at, String message) {

	@Override
	public String toString() {
		return at.isEmpty() ? message : at + ": " + message;
	}
}

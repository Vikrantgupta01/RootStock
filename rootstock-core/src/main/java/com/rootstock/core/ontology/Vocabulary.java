package com.rootstock.core.ontology;

import java.util.List;

/** A closed list of codes, each with a definition and the other words people use for it. */
public record Vocabulary(String name, String origin, String description, List<Term> terms) {

	public Vocabulary {
		terms = List.copyOf(terms);
	}

	public List<String> codes() {
		return terms.stream().map(Term::code).toList();
	}

	public record Term(String code, String definition, List<String> synonyms) {

		public Term {
			synonyms = List.copyOf(synonyms);
		}
	}
}

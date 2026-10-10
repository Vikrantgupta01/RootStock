package com.rootstock.core.ontology;

import java.util.List;
import java.util.stream.Collectors;

/** An ontology that could not be read or does not make sense; carries every problem found, not just the first. */
public class OntologyException extends RuntimeException {

	private final List<OntologyProblem> problems;

	public OntologyException(List<OntologyProblem> problems) {
		super(problems.stream().map(OntologyProblem::toString).collect(Collectors.joining("; ")));
		this.problems = List.copyOf(problems);
	}

	public List<OntologyProblem> problems() {
		return problems;
	}
}

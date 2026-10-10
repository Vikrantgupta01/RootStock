package com.rootstock.core.graph;

import java.util.List;
import java.util.stream.Collectors;

/** A graph or agent that cannot be used; carries every problem found. */
public class GraphDefinitionException extends RuntimeException {

	private final List<GraphProblem> problems;

	public GraphDefinitionException(String what, List<GraphProblem> problems) {
		super(what + ":\n  " + problems.stream().map(GraphProblem::toString).collect(Collectors.joining("\n  ")));
		this.problems = List.copyOf(problems);
	}

	public List<GraphProblem> problems() {
		return problems;
	}
}

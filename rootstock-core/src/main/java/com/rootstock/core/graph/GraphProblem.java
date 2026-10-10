package com.rootstock.core.graph;

/**
 * Something wrong with a pack's graph or agents, said so a person can fix it.
 *
 * @param file the file it is in, relative to the pack, e.g. {@code graph.yaml}
 * @param at   where in the file, e.g. {@code nodes[3].type} or {@code edges from validate}
 */
public record GraphProblem(String file, String at, String message) {

	@Override
	public String toString() {
		return file + (at == null || at.isEmpty() ? "" : " " + at) + ": " + message;
	}
}

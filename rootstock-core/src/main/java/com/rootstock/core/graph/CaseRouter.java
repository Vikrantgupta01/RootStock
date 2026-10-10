package com.rootstock.core.graph;

/**
 * A routing decision too complex for {@code when} conditions, written in Java
 * and referenced from {@code graph.yaml} as {@code router: <name>}. It must
 * return one of the edge's declared {@code targets} (or {@code END}).
 */
public interface CaseRouter {

	String name();

	String route(CaseState state);
}

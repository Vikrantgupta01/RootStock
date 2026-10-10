package com.rootstock.core.graph;

import org.bsc.langgraph4j.CompiledGraph;

/** A pack's graph, compiled and ready to run cases. */
public record CaseGraph(PackGraph definition, CompiledGraph<CaseState> compiled) {

	public String pack() {
		return definition.pack();
	}

	public String name() {
		return definition.graph().metadata().name();
	}

	public String version() {
		return definition.graph().metadata().version();
	}
}

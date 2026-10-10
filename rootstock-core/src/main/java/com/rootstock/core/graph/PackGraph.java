package com.rootstock.core.graph;

import java.util.Map;

/**
 * A pack's graph and the agents it uses, as loaded.
 *
 * @param pack   the pack's name
 * @param agents by agent name
 */
public record PackGraph(String pack, GraphDefinition graph, Map<String, AgentDefinition> agents) {

	public PackGraph {
		agents = Map.copyOf(agents);
	}
}

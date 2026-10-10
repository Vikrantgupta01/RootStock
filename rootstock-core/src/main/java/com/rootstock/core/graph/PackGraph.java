package com.rootstock.core.graph;

import com.rootstock.core.rules.RuleSpec;
import java.util.List;
import java.util.Map;

/**
 * A pack's graph, the agents it uses and its business rules, as loaded.
 *
 * @param pack   the pack's name
 * @param agents by agent name
 * @param rules  from rules.yaml, in order; empty when the pack has none
 */
public record PackGraph(String pack, GraphDefinition graph, Map<String, AgentDefinition> agents,
		List<RuleSpec> rules) {

	public PackGraph {
		agents = Map.copyOf(agents);
		rules = rules == null ? List.of() : List.copyOf(rules);
	}

	public PackGraph(String pack, GraphDefinition graph, Map<String, AgentDefinition> agents) {
		this(pack, graph, agents, List.of());
	}
}

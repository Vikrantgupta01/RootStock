package com.rootstock.core.graph;

import com.rootstock.core.rules.RuleSpec;
import java.util.List;
import java.util.Map;

/**
 * One of a pack's graphs, with the pack's agents and business rules, as loaded.
 *
 * @param pack   the pack's name
 * @param agents by agent name (all the pack's, shared by its graphs)
 * @param rules  from rules.yaml, in order; empty when the pack has none
 * @param source the file it came from, e.g. {@code graph.yaml} or {@code graphs/case-intake.yaml}
 */
public record PackGraph(String pack, GraphDefinition graph, Map<String, AgentDefinition> agents,
		List<RuleSpec> rules, String source) {

	public PackGraph {
		agents = Map.copyOf(agents);
		rules = rules == null ? List.of() : List.copyOf(rules);
		source = source == null ? PackGraphLoader.GRAPH_FILE : source;
	}

	public PackGraph(String pack, GraphDefinition graph, Map<String, AgentDefinition> agents, List<RuleSpec> rules) {
		this(pack, graph, agents, rules, null);
	}

	public PackGraph(String pack, GraphDefinition graph, Map<String, AgentDefinition> agents) {
		this(pack, graph, agents, List.of(), null);
	}

	public String name() {
		return graph.metadata().name();
	}
}

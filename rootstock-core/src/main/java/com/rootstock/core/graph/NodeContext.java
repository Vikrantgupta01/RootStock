package com.rootstock.core.graph;

import com.rootstock.core.ontology.ResolvedOntology;

/**
 * What a factory gets to build one node.
 *
 * @param agent    the agent definition, for an agent node; null for a plain node
 * @param ontology the pack's ontology; null when the pack has none
 */
public record NodeContext(String pack, GraphDefinition.NodeSpec node, AgentDefinition agent,
		ResolvedOntology ontology) {

	/** The node's type: its own, or its agent's. */
	public String type() {
		return agent == null ? node.type() : agent.spec().type();
	}
}

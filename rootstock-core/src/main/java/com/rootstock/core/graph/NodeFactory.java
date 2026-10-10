package com.rootstock.core.graph;

import org.bsc.langgraph4j.action.NodeAction;

/**
 * A building block a graph can use: a node type (used as {@code type:} in
 * graph.yaml) or an agent type (used as {@code spec.type:} in an agent file).
 * Rootstock provides a fixed set; packs only combine and configure them.
 */
public interface NodeFactory {

	enum Kind {
		/** Used directly by a graph node, e.g. {@code rules}, {@code human-review}. */
		NODE,
		/** Used by an agent definition, e.g. {@code structured-extraction}. */
		AGENT
	}

	Kind kind();

	/** The name YAML uses, e.g. {@code structured-extraction}. */
	String type();

	/** The node's work for one step: read the state, return the channels to update. */
	NodeAction<CaseState> create(NodeContext context);
}

package com.rootstock.core.graph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Every node and agent type Rootstock knows, by name. */
public final class NodeRegistry {

	private final Map<String, NodeFactory> nodes = new LinkedHashMap<>();
	private final Map<String, NodeFactory> agents = new LinkedHashMap<>();

	public NodeRegistry(Collection<? extends NodeFactory> factories) {
		for (NodeFactory f : factories) {
			Map<String, NodeFactory> into = f.kind() == NodeFactory.Kind.NODE ? nodes : agents;
			if (into.put(f.type(), f) != null) {
				throw new IllegalStateException("Two " + f.kind().name().toLowerCase() + " types are called '"
						+ f.type() + "'");
			}
		}
	}

	public Optional<NodeFactory> node(String type) {
		return Optional.ofNullable(nodes.get(type));
	}

	public Optional<NodeFactory> agent(String type) {
		return Optional.ofNullable(agents.get(type));
	}

	public Set<String> nodeTypes() {
		return new TreeSet<>(nodes.keySet());
	}

	public Set<String> agentTypes() {
		return new TreeSet<>(agents.keySet());
	}
}

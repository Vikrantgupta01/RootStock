package com.rootstock.core.tools;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every tool Rootstock may call, and which nodes may call which. Built once at
 * startup and checked then, so a bad configuration stops the app instead of
 * surfacing as a refused call in front of a user.
 *
 * <p>Safety invariant, enforced here whatever the configuration says: a WRITE
 * tool may only be allowed in a node declared as a write node (in the design,
 * {@code commit}, after a human approval).
 */
public final class ToolCatalog {

	private final Map<String, ToolDefinition> tools;
	private final Map<String, Set<String>> allowlists;
	private final Set<String> writeNodes;

	public ToolCatalog(Map<String, ToolDefinition> tools, Map<String, ? extends Collection<String>> allowlists,
			Set<String> writeNodes) {
		this.tools = Map.copyOf(tools);
		this.writeNodes = Set.copyOf(writeNodes);
		Map<String, Set<String>> copy = new TreeMap<>();
		allowlists.forEach((node, names) -> copy.put(node, Set.copyOf(names)));
		this.allowlists = Map.copyOf(copy);
		validate();
	}

	public static ToolCatalog empty() {
		return new ToolCatalog(Map.of(), Map.of(), Set.of());
	}

	public Optional<ToolDefinition> tool(String name) {
		return Optional.ofNullable(tools.get(name));
	}

	public boolean allows(String node, String tool) {
		return allowlists.getOrDefault(node, Set.of()).contains(tool);
	}

	/** The tools a node may call, sorted; empty for an unknown node. */
	public List<String> allowed(String node) {
		return allowlists.getOrDefault(node, Set.of()).stream().sorted().toList();
	}

	public Set<String> nodes() {
		return allowlists.keySet();
	}

	/** The only nodes where WRITE tools may be allowed. */
	public Set<String> writeNodes() {
		return Set.copyOf(writeNodes);
	}

	public List<ToolDefinition> tools() {
		return tools.values().stream().sorted(Comparator.comparing(ToolDefinition::name)).toList();
	}

	private void validate() {
		allowlists.forEach((node, names) -> {
			for (String name : names) {
				ToolDefinition tool = tools.get(name);
				if (tool == null) {
					throw new IllegalStateException("Allowlist for node '" + node + "' names unknown tool '" + name
							+ "'; known tools: " + new TreeMap<>(tools).keySet());
				}
				if (tool.access() == ToolAccess.WRITE && !writeNodes.contains(node)) {
					throw new IllegalStateException("Write tool '" + name + "' is allowed in node '" + node
							+ "', which is not a write node " + writeNodes
							+ ". Write tools may only run where a human approval precedes them.");
				}
			}
		});
	}
}

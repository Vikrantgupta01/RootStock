package com.rootstock.core.graph;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pack's case-processing graph as written in {@code graph.yaml}: which nodes
 * there are, how they connect, and how a run is paused. Read by
 * {@link PackGraphLoader}, checked by {@link GraphValidator}, turned into a
 * LangGraph4j graph by {@link GraphCompiler}.
 */
public record GraphDefinition(Metadata metadata, State state, List<NodeSpec> nodes, List<EdgeSpec> edges,
		Runtime runtime) {

	public static final String START = "START";
	public static final String END = "END";

	public GraphDefinition {
		state = state == null ? new State(Map.of()) : state;
		nodes = List.copyOf(nodes);
		edges = List.copyOf(edges);
		runtime = runtime == null ? new Runtime(null, null, null, null) : runtime;
	}

	public record Metadata(String name, String version, String pack, String description) {
	}

	public record State(Map<String, ChannelSpec> channels) {

		public State {
			channels = channels == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(channels));
		}
	}

	/** How a channel combines a node's update with what is already there. */
	public enum Reducer {
		/** The new value replaces the old one. */
		@JsonProperty("replace") REPLACE,
		/** Maps are merged, keys from the update winning. */
		@JsonProperty("merge") MERGE,
		/** Items are added to the list. */
		@JsonProperty("append") APPEND
	}

	public record ChannelSpec(Reducer reducer, String description) {
	}

	/**
	 * A node is either a building block ({@code type}) or an agent defined in
	 * {@code agents/} ({@code agent}), never both.
	 */
	public record NodeSpec(String id, String type, String agent, String description, Map<String, Object> config) {

		public NodeSpec {
			config = config == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(config));
		}
	}

	/**
	 * An edge goes to one node ({@code to}), picks one by conditions
	 * ({@code route}), or asks a named Java router ({@code router}, which may
	 * return any of {@code targets}).
	 */
	public record EdgeSpec(String from, String to, List<RouteSpec> route, String router, List<String> targets) {

		public EdgeSpec {
			route = route == null ? List.of() : List.copyOf(route);
			targets = targets == null ? List.of() : List.copyOf(targets);
		}

		public boolean routed() {
			return !route.isEmpty() || router != null;
		}

		/** Every node this edge may lead to. */
		public List<String> destinations() {
			if (router != null) {
				return targets;
			}
			if (route.isEmpty()) {
				return to == null ? List.of() : List.of(to);
			}
			return route.stream().map(RouteSpec::target).filter(t -> t != null).distinct().toList();
		}
	}

	/** One branch of a route: {@code when} conditions and {@code to}, or a {@code default}. */
	public record RouteSpec(Map<String, Object> when, String to, @JsonProperty("default") String defaultTarget) {

		public RouteSpec {
			when = when == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(when));
		}

		public boolean isDefault() {
			return defaultTarget != null;
		}

		public String target() {
			return isDefault() ? defaultTarget : to;
		}
	}

	/**
	 * @param checkpointer    where paused runs are kept; only {@code memory} so far
	 * @param interruptBefore nodes a run pauses before, e.g. for human review
	 * @param interruptAfter  nodes a run pauses after, e.g. once questions are asked
	 * @param maxSteps        the most node steps one run may take
	 */
	public record Runtime(String checkpointer, List<String> interruptBefore, List<String> interruptAfter,
			Integer maxSteps) {

		public static final int DEFAULT_MAX_STEPS = 40;

		public Runtime {
			checkpointer = checkpointer == null ? "memory" : checkpointer;
			interruptBefore = interruptBefore == null ? List.of() : List.copyOf(interruptBefore);
			interruptAfter = interruptAfter == null ? List.of() : List.copyOf(interruptAfter);
			maxSteps = maxSteps == null ? DEFAULT_MAX_STEPS : maxSteps;
		}
	}

	public NodeSpec node(String id) {
		return nodes.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
	}
}

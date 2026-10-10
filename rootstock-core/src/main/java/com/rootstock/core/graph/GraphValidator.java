package com.rootstock.core.graph;

import com.rootstock.core.ontology.ResolvedOntology;
import com.rootstock.core.tools.ToolAccess;
import com.rootstock.core.tools.ToolCatalog;
import com.rootstock.core.tools.ToolDefinition;
import com.rootstock.core.rules.RuleEngine;
import com.rootstock.core.rules.RuleKind;
import com.rootstock.core.rules.RuleKinds;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a pack's graph and agents before anything runs, so a broken or unsafe
 * definition stops Rootstock at startup with every problem listed. Beyond
 * references (every node type, agent, edge target, channel and projection
 * exists; every node is reachable), it enforces the safety invariants:
 *
 * <ul>
 * <li>only a {@code tool-executor} node may set {@code allowWrites}, and only it
 * may be a write node in tools.yaml;</li>
 * <li>every path from START to a node that may write passes a
 * {@code human-review} node a person decides at: one the run pauses before, or
 * any, in a graph only an approver's decision starts;</li>
 * <li>an agent's tools exist, are read-only, and are on its node's allowlist in
 * tools.yaml.</li>
 * </ul>
 */
public final class GraphValidator {

	public static final String HUMAN_REVIEW = "human-review";
	public static final String TOOL_EXECUTOR = "tool-executor";
	static final String GRAPH = PackGraphLoader.GRAPH_FILE;

	/** Node config keys whose value names an agent, e.g. the rules node's judge. */
	static final List<String> AGENT_REFERENCES = List.of("judge", "questionWriter");

	/**
	 * What the graph is checked against.
	 *
	 * @param ontology        the pack's ontology; null when it has none or it is invalid
	 * @param ontologyMissing why there is no ontology, for messages; null when there is one
	 */
	/**
	 * @param modelProfiles the configured model profiles an agent's {@code model} may name; null to not check
	 * @param ruleKinds     the rule kinds rules.yaml may use, by name; null for the built-in ones
	 */
	public record Context(NodeRegistry registry, Set<String> routers, ToolCatalog tools, ResolvedOntology ontology,
			String ontologyMissing, Set<String> modelProfiles, Map<String, RuleKind> ruleKinds) {

		public Context {
			ruleKinds = ruleKinds == null ? RuleKinds.of(List.of()) : ruleKinds;
		}

		public Context(NodeRegistry registry, Set<String> routers, ToolCatalog tools, ResolvedOntology ontology,
				String ontologyMissing, Set<String> modelProfiles) {
			this(registry, routers, tools, ontology, ontologyMissing, modelProfiles, null);
		}

		public Context(NodeRegistry registry, Set<String> routers, ToolCatalog tools, ResolvedOntology ontology,
				String ontologyMissing) {
			this(registry, routers, tools, ontology, ontologyMissing, null, null);
		}
	}

	/** One graph, as the only one in its pack. */
	public List<GraphProblem> validate(PackGraph pack, Context context) {
		return validatePack(List.of(pack), context);
	}

	/**
	 * All of a pack's graphs: each on its own, then what spans them (agents used
	 * by none, graph names, one graph started by submit, where outcomes lead).
	 */
	public List<GraphProblem> validatePack(List<PackGraph> graphs, Context context) {
		List<GraphProblem> problems = new ArrayList<>();
		for (PackGraph g : graphs) {
			for (GraphProblem p : validateGraph(g, context)) {
				// Each graph's checks name graph.yaml; say which file it really is.
				GraphProblem located = p.file().equals(GRAPH) ? new GraphProblem(g.source(), p.at(), p.message()) : p;
				if (!problems.contains(located)) {
					problems.add(located);
				}
			}
		}
		if (graphs.isEmpty()) {
			return problems;
		}
		PackGraph any = graphs.getFirst();
		Set<String> names = new HashSet<>();
		for (PackGraph g : graphs) {
			if (!names.add(g.name())) {
				problems.add(new GraphProblem(g.source(), "metadata.name", "graph '" + g.name() + "' is defined twice"));
			}
		}
		List<String> entries = graphs.stream().filter(g -> !g.graph().trigger().decision()).map(PackGraph::name)
				.sorted().toList();
		if (entries.size() != 1) {
			problems.add(new GraphProblem(any.source(), "trigger", "a pack needs exactly one graph a case starts with "
					+ "(trigger kind submit); it has " + entries.size() + (entries.isEmpty() ? "" : ": " + entries)));
		}
		Map<String, PackGraph> byName = new HashMap<>();
		graphs.forEach(g -> byName.put(g.name(), g));
		for (PackGraph g : graphs) {
			g.graph().outcomes().forEach((node, outcomes) -> {
				for (int i = 0; i < outcomes.size(); i++) {
					String next = outcomes.get(i).next();
					if (next == null) {
						continue;
					}
					PackGraph target = byName.get(next);
					String at = "outcomes." + node + "[" + i + "].next";
					if (target == null) {
						problems.add(new GraphProblem(g.source(), at, "unknown graph '" + next + "'; the pack has "
								+ new TreeSet<>(byName.keySet())));
					}
					else if (!target.graph().trigger().decision()) {
						problems.add(new GraphProblem(g.source(), at, "graph '" + next + "' starts a new case (trigger "
								+ "submit); a case can only go on to a graph started by a decision"));
					}
				}
			});
		}
		// An agent may be used by any of the graphs, as a node or by a node's config (e.g. a judge).
		for (AgentDefinition a : any.agents().values()) {
			boolean used = graphs.stream().flatMap(g -> g.graph().nodes().stream())
					.anyMatch(n -> a.name().equals(n.agent()) || n.config().values().stream().anyMatch(a.name()::equals));
			if (!used) {
				problems.add(new GraphProblem(PackGraphLoader.AGENTS_DIR + "/" + a.name() + ".yaml", "",
						"agent '" + a.name() + "' is not used by any node"));
			}
		}
		return problems;
	}

	private List<GraphProblem> validateGraph(PackGraph pack, Context context) {
		List<GraphProblem> problems = new ArrayList<>();
		GraphDefinition g = pack.graph();
		Set<String> ids = new LinkedHashSet<>();

		// Nodes: unique ids, exactly one of type/agent, and the type exists.
		for (int i = 0; i < g.nodes().size(); i++) {
			GraphDefinition.NodeSpec n = g.nodes().get(i);
			String at = "nodes[" + i + "] (" + n.id() + ")";
			if (n.id().equalsIgnoreCase(GraphDefinition.START) || n.id().equalsIgnoreCase(GraphDefinition.END)) {
				problems.add(new GraphProblem(GRAPH, at, "START and END are reserved names"));
			}
			if (!ids.add(n.id())) {
				problems.add(new GraphProblem(GRAPH, at, "node id '" + n.id() + "' is used twice"));
			}
			if ((n.type() == null) == (n.agent() == null)) {
				problems.add(new GraphProblem(GRAPH, at, "give exactly one of 'type' (a node type) or 'agent' (an agent in agents/)"));
			}
			else if (n.type() != null && context.registry().node(n.type()).isEmpty()) {
				problems.add(new GraphProblem(GRAPH, at, "unknown node type '" + n.type() + "'; known: "
						+ context.registry().nodeTypes()));
			}
			else if (n.agent() != null && !pack.agents().containsKey(n.agent())) {
				problems.add(new GraphProblem(GRAPH, at, "unknown agent '" + n.agent() + "'; agents/ has "
						+ new TreeSet<>(pack.agents().keySet())));
			}
			for (String key : AGENT_REFERENCES) {
				Object ref = n.config().get(key);
				if (ref != null && !pack.agents().containsKey(String.valueOf(ref))) {
					problems.add(new GraphProblem(GRAPH, at + " config." + key, "unknown agent '" + ref + "'; agents/ has "
							+ new TreeSet<>(pack.agents().keySet())));
				}
			}
		}

		Set<String> channels = channels(g);
		Set<String> lists = lists(g);
		Set<String> objects = objects(g);
		agents(pack, context, channels, problems);
		// Rules decide routing (a BLOCKING issue sends the case back), so a broken one stops startup.
		RuleEngine.problems(pack.rules(), context.ruleKinds())
				.forEach(p -> problems.add(new GraphProblem(PackGraphLoader.RULES_FILE, "rules", p)));
		Map<String, List<String>> next = edges(g, ids, channels, lists, objects, context, problems);
		reachability(g, ids, next, problems);
		runtime(g, ids, problems);
		trigger(g, problems);
		outcomes(g, ids, next, channels, lists, objects, problems);
		writes(pack, context, next, problems);
		return problems;
	}

	private void trigger(GraphDefinition g, List<GraphProblem> problems) {
		if (g.trigger().decision() && g.trigger().approverRoles().isEmpty()) {
			problems.add(new GraphProblem(GRAPH, "trigger.approverRoles", "a graph started by a decision says who may "
					+ "make it"));
		}
	}

	/**
	 * When a graph says how the case stands after a run, it says so for every
	 * node a run can end after, with a default last.
	 */
	private void outcomes(GraphDefinition g, Set<String> ids, Map<String, List<String>> next, Set<String> channels,
			Set<String> lists, Set<String> objects, List<GraphProblem> problems) {
		if (g.outcomes().isEmpty()) {
			return;
		}
		g.outcomes().forEach((node, outcomes) -> {
			String at = "outcomes." + node;
			if (!ids.contains(node)) {
				problems.add(new GraphProblem(GRAPH, at, "unknown node '" + node + "'"));
				return;
			}
			if (!next.getOrDefault(node, List.of()).contains(GraphDefinition.END)) {
				problems.add(new GraphProblem(GRAPH, at, "no run can end after '" + node + "' (it has no edge to END)"));
			}
			for (int i = 0; i < outcomes.size(); i++) {
				GraphDefinition.Outcome o = outcomes.get(i);
				boolean last = i == outcomes.size() - 1;
				if (o.when().isEmpty() != last) {
					problems.add(new GraphProblem(GRAPH, at + "[" + i + "]", last ? "the last outcome is the default "
							+ "and has no 'when'" : "only the last outcome may leave out 'when'"));
				}
				o.when().forEach((k, v) -> {
					try {
						RouteConditions.parse(k, v, lists, objects, channels);
					}
					catch (IllegalArgumentException ex) {
						problems.add(new GraphProblem(GRAPH, at + "[" + o.status() + "]", ex.getMessage()));
					}
				});
			}
		});
		next.forEach((from, targets) -> {
			if (targets.contains(GraphDefinition.END) && !from.equals(GraphDefinition.START)
					&& !g.outcomes().containsKey(from)) {
				problems.add(new GraphProblem(GRAPH, "outcomes", "a run can end after '" + from + "'; say how the case "
						+ "then stands"));
			}
		});
	}

	/** Every channel a node or route may use: the declared ones and the engine's own. */
	static Set<String> channels(GraphDefinition g) {
		Set<String> known = new TreeSet<>(g.state().channels().keySet());
		known.addAll(CaseState.ENGINE_KEYS);
		return known;
	}

	/** Channels that may hold a list: append ones, and replace ones (whose type the reducer doesn't say). */
	static Set<String> lists(GraphDefinition g) {
		Set<String> lists = new HashSet<>();
		g.state().channels().forEach((name, spec) -> {
			if (spec.reducer() != GraphDefinition.Reducer.MERGE) {
				lists.add(name);
			}
		});
		return lists;
	}

	/** Channels that may hold an object: merge and replace ones, and the engine's own. */
	static Set<String> objects(GraphDefinition g) {
		Set<String> objects = new HashSet<>(CaseState.ENGINE_KEYS);
		g.state().channels().forEach((name, spec) -> {
			if (spec.reducer() != GraphDefinition.Reducer.APPEND) {
				objects.add(name);
			}
		});
		return objects;
	}

	private void agents(PackGraph pack, Context context, Set<String> channels, List<GraphProblem> problems) {
		Map<String, List<String>> usedBy = new HashMap<>();
		pack.graph().nodes().stream().filter(n -> n.agent() != null)
				.forEach(n -> usedBy.computeIfAbsent(n.agent(), k -> new ArrayList<>()).add(n.id()));

		for (AgentDefinition a : pack.agents().values()) {
			boolean usedHere = usedBy.containsKey(a.name()) || pack.graph().nodes().stream()
					.anyMatch(n -> n.config().values().stream().anyMatch(v -> a.name().equals(v)));
			if (!usedHere) {
				// Checked against the graph that uses it; one no graph uses is reported for the pack.
				continue;
			}
			String file = PackGraphLoader.AGENTS_DIR + "/" + a.name() + ".yaml";
			AgentDefinition.Spec spec = a.spec();
			if (context.registry().agent(spec.type()).isEmpty()) {
				problems.add(new GraphProblem(file, "spec.type", "unknown agent type '" + spec.type() + "'; known: "
						+ context.registry().agentTypes()));
			}
			if (spec.model() != null && context.modelProfiles() != null
					&& !context.modelProfiles().contains(spec.model())) {
				problems.add(new GraphProblem(file, "spec.model", "unknown model profile '" + spec.model()
						+ "'; configured (rootstock.llm.profiles): " + new TreeSet<>(context.modelProfiles())));
			}
			String writeTo = spec.output().writeTo();
			if (!channels.contains(writeTo)) {
				problems.add(new GraphProblem(file, "spec.output.writeTo", "unknown state channel '" + writeTo
						+ "'; declare it under state.channels in graph.yaml"));
			}
			spec.input().forEach((name, path) -> {
				String root = path.substring(2).split("\\.")[0];
				if (!channels.contains(root)) {
					problems.add(new GraphProblem(file, "spec.input." + name, "'" + path + "' reads unknown state channel '"
							+ root + "'; known: " + channels));
				}
			});
			plan(spec, file, channels, problems);
			String projection = spec.output().projection();
			if (projection != null) {
				if (context.ontology() == null) {
					problems.add(new GraphProblem(file, "spec.output.projection", "projection '" + projection
							+ "' cannot be checked: " + context.ontologyMissing()));
				}
				else if (!context.ontology().own().projections().containsKey(projection)) {
					problems.add(new GraphProblem(file, "spec.output.projection", "unknown projection '" + projection
							+ "'; the ontology has " + context.ontology().own().projections().keySet()));
				}
			}
			if (spec.tools() != null) {
				tools(a, file, usedBy.getOrDefault(a.name(), List.of()), context.tools(), problems);
			}
		}
	}

	/** A plan's tools must be among the agent's own, and its paths must read known channels. */
	private static void plan(AgentDefinition.Spec spec, String file, Set<String> channels, List<GraphProblem> problems) {
		List<String> allowed = spec.tools() == null ? List.of() : spec.tools().allow();
		for (int i = 0; i < spec.plan().size(); i++) {
			AgentDefinition.PlanStep step = spec.plan().get(i);
			String at = "spec.plan[" + i + "]";
			if (!allowed.contains(step.tool())) {
				problems.add(new GraphProblem(file, at + ".tool", "tool '" + step.tool()
						+ "' is not in the agent's tools.allow " + allowed));
			}
			List<String> paths = new ArrayList<>(step.with().values());
			if (step.forEach() != null) {
				paths.add(step.forEach());
			}
			for (String path : paths) {
				if (path.startsWith("$item.")) {
					if (step.forEach() == null) {
						problems.add(new GraphProblem(file, at + ".with", "'" + path + "' reads $item, but the step has no forEach"));
					}
					continue;
				}
				String root = path.substring(2).split("\\.")[0];
				if (!channels.contains(root)) {
					problems.add(new GraphProblem(file, at, "'" + path + "' reads unknown state channel '" + root
							+ "'; known: " + channels));
				}
			}
		}
	}

	/** An agent's tools must exist, be read-only, and be on the allowlist of every node that uses the agent. */
	private void tools(AgentDefinition a, String file, List<String> nodes, ToolCatalog catalog,
			List<GraphProblem> problems) {
		for (String name : a.spec().tools().allow()) {
			Optional<ToolDefinition> tool = catalog.tool(name);
			if (tool.isEmpty()) {
				problems.add(new GraphProblem(file, "spec.tools.allow", "unknown tool '" + name
						+ "'; tools.yaml (ROOTSTOCK_TOOLS_FILE) defines " + catalog.tools().stream().map(ToolDefinition::name).toList()));
				continue;
			}
			if (!tool.get().connection().equals(a.spec().tools().connection())) {
				problems.add(new GraphProblem(file, "spec.tools.allow", "tool '" + name + "' is on connection '"
						+ tool.get().connection() + "', not '" + a.spec().tools().connection() + "'"));
			}
			if (tool.get().access() == ToolAccess.WRITE) {
				problems.add(new GraphProblem(file, "spec.tools.allow", "tool '" + name
						+ "' writes; agents may only use read tools (writes happen in a tool-executor node, after review)"));
			}
			for (String node : nodes) {
				if (!catalog.allows(node, name)) {
					problems.add(new GraphProblem(file, "spec.tools.allow", "tool '" + name + "' is not on node '" + node
							+ "''s allowlist in tools.yaml (" + catalog.allowed(node) + ")"));
				}
			}
		}
	}

	/** Checks edges; returns, for each node, where it can go next. */
	private Map<String, List<String>> edges(GraphDefinition g, Set<String> ids, Set<String> channels, Set<String> lists,
			Set<String> objects, Context context, List<GraphProblem> problems) {
		Map<String, List<String>> next = new HashMap<>();
		Map<String, Integer> outgoing = new HashMap<>();
		for (int i = 0; i < g.edges().size(); i++) {
			GraphDefinition.EdgeSpec e = g.edges().get(i);
			String at = "edges[" + i + "] (from " + e.from() + ")";
			if (!e.from().equals(GraphDefinition.START) && !ids.contains(e.from())) {
				problems.add(new GraphProblem(GRAPH, at, "unknown node '" + e.from() + "'"));
			}
			if (e.from().equals(GraphDefinition.END)) {
				problems.add(new GraphProblem(GRAPH, at, "nothing can follow END"));
			}
			outgoing.merge(e.from(), 1, Integer::sum);

			int forms = (e.to() != null ? 1 : 0) + (!e.route().isEmpty() ? 1 : 0) + (e.router() != null ? 1 : 0);
			if (forms != 1) {
				problems.add(new GraphProblem(GRAPH, at, "give exactly one of 'to', 'route' or 'router'"));
				continue;
			}
			if (e.router() != null) {
				if (!context.routers().contains(e.router())) {
					problems.add(new GraphProblem(GRAPH, at, "unknown router '" + e.router() + "'; known: "
							+ new TreeSet<>(context.routers())));
				}
				if (e.targets().isEmpty()) {
					problems.add(new GraphProblem(GRAPH, at, "a router edge lists its possible 'targets'"));
				}
			}
			else if (!e.targets().isEmpty()) {
				problems.add(new GraphProblem(GRAPH, at, "'targets' only goes with 'router'"));
			}
			if (!e.route().isEmpty()) {
				route(e, at, channels, lists, objects, problems);
			}
			if (e.from().equals(GraphDefinition.START) && e.routed()) {
				problems.add(new GraphProblem(GRAPH, at, "the edge from START goes to one node"));
			}
			for (String target : e.destinations()) {
				if (!target.equals(GraphDefinition.END) && !ids.contains(target)) {
					problems.add(new GraphProblem(GRAPH, at, "unknown target '" + target + "'"));
				}
				if (target.equals(GraphDefinition.START)) {
					problems.add(new GraphProblem(GRAPH, at, "nothing can go back to START"));
				}
			}
			next.computeIfAbsent(e.from(), k -> new ArrayList<>()).addAll(e.destinations());
		}
		outgoing.forEach((from, count) -> {
			if (count > 1) {
				problems.add(new GraphProblem(GRAPH, "edges from " + from, count
						+ " edges leave this node; use one edge with a 'route' to branch"));
			}
		});
		if (!outgoing.containsKey(GraphDefinition.START)) {
			problems.add(new GraphProblem(GRAPH, "edges", "no edge from START"));
		}
		for (String id : ids) {
			if (!outgoing.containsKey(id)) {
				problems.add(new GraphProblem(GRAPH, "node " + id, "no edge leaves this node; add one (to END if the run ends here)"));
			}
		}
		return next;
	}

	private void route(GraphDefinition.EdgeSpec e, String at, Set<String> channels, Set<String> lists,
			Set<String> objects, List<GraphProblem> problems) {
		List<GraphDefinition.RouteSpec> route = e.route();
		for (int j = 0; j < route.size(); j++) {
			GraphDefinition.RouteSpec r = route.get(j);
			String where = at + " route[" + j + "]";
			if (r.isDefault()) {
				if (r.to() != null || !r.when().isEmpty()) {
					problems.add(new GraphProblem(GRAPH, where, "a default branch has only 'default'"));
				}
				if (j != route.size() - 1) {
					problems.add(new GraphProblem(GRAPH, where, "the default branch must come last"));
				}
				continue;
			}
			if (r.when().isEmpty() || r.to() == null) {
				problems.add(new GraphProblem(GRAPH, where, "a branch needs 'when' and 'to' (or is the 'default')"));
				continue;
			}
			r.when().forEach((key, value) -> {
				try {
					RouteConditions.parse(key, value, lists, objects, channels);
				}
				catch (IllegalArgumentException ex) {
					problems.add(new GraphProblem(GRAPH, where, ex.getMessage()));
				}
			});
		}
		if (route.stream().noneMatch(GraphDefinition.RouteSpec::isDefault)) {
			problems.add(new GraphProblem(GRAPH, at, "the route needs a 'default' branch, so a run never has nowhere to go"));
		}
	}

	private void reachability(GraphDefinition g, Set<String> ids, Map<String, List<String>> next,
			List<GraphProblem> problems) {
		Set<String> reached = reach(GraphDefinition.START, next, Set.of());
		for (String id : ids) {
			if (!reached.contains(id)) {
				problems.add(new GraphProblem(GRAPH, "node " + id, "cannot be reached from START"));
			}
		}
		if (!reached.contains(GraphDefinition.END)) {
			problems.add(new GraphProblem(GRAPH, "edges", "no path from START reaches END"));
		}
	}

	private void runtime(GraphDefinition g, Set<String> ids, List<GraphProblem> problems) {
		GraphDefinition.Runtime r = g.runtime();
		for (String n : r.interruptBefore()) {
			if (!ids.contains(n)) {
				problems.add(new GraphProblem(GRAPH, "runtime.interruptBefore", "unknown node '" + n + "'"));
			}
		}
		for (String n : r.interruptAfter()) {
			if (!ids.contains(n)) {
				problems.add(new GraphProblem(GRAPH, "runtime.interruptAfter", "unknown node '" + n + "'"));
			}
		}
	}

	/** Writes happen only in tool-executor nodes, and never without a human review first. */
	private void writes(PackGraph pack, Context context, Map<String, List<String>> next, List<GraphProblem> problems) {
		GraphDefinition g = pack.graph();
		Set<String> writers = new LinkedHashSet<>();
		for (GraphDefinition.NodeSpec n : g.nodes()) {
			boolean allowWrites = Boolean.TRUE.equals(n.config().get("allowWrites"));
			boolean executor = TOOL_EXECUTOR.equals(n.type());
			if (allowWrites && !executor) {
				problems.add(new GraphProblem(GRAPH, "node " + n.id(), "only a tool-executor node may set allowWrites"));
			}
			if (allowWrites || context.tools().writeNodes().contains(n.id())) {
				writers.add(n.id());
				if (!executor) {
					problems.add(new GraphProblem(GRAPH, "node " + n.id(), "tools.yaml lists it as a write node, but it is not a tool-executor"));
				}
				else if (!allowWrites) {
					problems.add(new GraphProblem(GRAPH, "node " + n.id(), "tools.yaml lists it as a write node; set config.allowWrites: true to make that explicit here too"));
				}
			}
		}
		if (writers.isEmpty()) {
			return;
		}
		// Gates: human-review nodes a person decides at. Either the run pauses before
		// one, or the whole graph is started only by an approver's decision (which the
		// review node then records). Remove the gates, and no writer may be reachable.
		boolean approverStarted = g.trigger().decision() && !g.trigger().approverRoles().isEmpty();
		Set<String> gates = new HashSet<>();
		for (GraphDefinition.NodeSpec n : g.nodes()) {
			if (HUMAN_REVIEW.equals(n.type()) && (approverStarted || g.runtime().interruptBefore().contains(n.id()))) {
				gates.add(n.id());
			}
		}
		Set<String> ungated = reach(GraphDefinition.START, next, gates);
		for (String writer : writers) {
			if (ungated.contains(writer)) {
				problems.add(new GraphProblem(GRAPH, "node " + writer, "can be reached from START without passing a "
						+ "human-review node that a person decides at (one in runtime.interruptBefore, or any, in a "
						+ "graph started by an approver's decision); every write needs a human approval first"
						+ (gates.isEmpty() ? " (there is no such node)" : " (gates: " + new TreeSet<>(gates) + ")")));
			}
		}
	}

	/** Every node reachable from {@code from}, never passing through {@code blocked}. */
	private static Set<String> reach(String from, Map<String, List<String>> next, Set<String> blocked) {
		Set<String> seen = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>(List.of(from));
		while (!queue.isEmpty()) {
			String n = queue.poll();
			if (!seen.add(n)) {
				continue;
			}
			for (String m : next.getOrDefault(n, List.of())) {
				if (!blocked.contains(m)) {
					queue.add(m);
				}
			}
		}
		return seen;
	}
}

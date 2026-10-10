package com.rootstock.core.graph;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.rootstock.core.ontology.ResolvedOntology;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

/**
 * Turns a validated {@link PackGraph} into a LangGraph4j graph: each node built
 * by its factory and wrapped so the {@link NodeListener} hears when it starts and
 * ends; each edge plain, routed by conditions, or routed by a named
 * {@link CaseRouter}; pauses ({@code interruptBefore/After}) and the step limit
 * from {@code runtime}. Runs are checkpointed in memory for now, so a paused run
 * does not survive a restart.
 */
public final class GraphCompiler {

	private final NodeRegistry registry;
	private final Map<String, CaseRouter> routers;
	private final NodeListener listener;

	public GraphCompiler(NodeRegistry registry, List<CaseRouter> routers, NodeListener listener) {
		this.registry = registry;
		this.routers = new HashMap<>();
		routers.forEach(r -> this.routers.put(r.name(), r));
		this.listener = listener;
	}

	public CaseGraph compile(PackGraph pack, ResolvedOntology ontology) {
		GraphDefinition g = pack.graph();
		Set<String> channels = GraphValidator.channels(g);
		Set<String> lists = GraphValidator.lists(g);
		Set<String> objects = GraphValidator.objects(g);
		try {
			StateGraph<CaseState> graph = new StateGraph<>(StateSchemas.from(g.state()), CaseState::new);
			for (GraphDefinition.NodeSpec node : g.nodes()) {
				AgentDefinition agent = node.agent() == null ? null : pack.agents().get(node.agent());
				NodeContext context = new NodeContext(pack.pack(), node, agent, ontology);
				NodeFactory factory = agent == null ? registry.node(node.type()).orElseThrow()
						: registry.agent(agent.spec().type()).orElseThrow();
				graph.addNode(node.id(), node_async(observed(node.id(), factory.create(context))));
			}
			for (GraphDefinition.EdgeSpec edge : g.edges()) {
				String from = id(edge.from());
				if (!edge.routed()) {
					graph.addEdge(from, id(edge.to()));
					continue;
				}
				Function<CaseState, String> decide = edge.router() != null ? routed(edge)
						: RouteConditions.compile(edge.route(), lists, objects, channels);
				Map<String, String> targets = new HashMap<>();
				edge.destinations().forEach(t -> targets.put(t, id(t)));
				graph.addConditionalEdges(from, edge_async(decide::apply), targets);
			}
			return new CaseGraph(pack, graph.compile(CompileConfig.builder()
					.checkpointSaver(new MemorySaver())
					.interruptsBefore(g.runtime().interruptBefore())
					.interruptsAfter(g.runtime().interruptAfter())
					.recursionLimit(g.runtime().maxSteps())
					.build()));
		}
		catch (GraphStateException e) {
			throw new IllegalStateException("Pack '" + pack.pack() + "': LangGraph4j rejected the graph: " + e.getMessage(), e);
		}
	}

	private Function<CaseState, String> routed(GraphDefinition.EdgeSpec edge) {
		CaseRouter router = routers.get(edge.router());
		return state -> {
			String target = router.route(state);
			if (!edge.targets().contains(target)) {
				throw new IllegalStateException("Router '" + edge.router() + "' chose '" + target
						+ "', which is not one of its targets " + edge.targets());
			}
			return target;
		};
	}

	private NodeAction<CaseState> observed(String node, NodeAction<CaseState> action) {
		return state -> {
			String runId = state.runId();
			listener.nodeStarted(runId, node);
			try {
				Map<String, Object> update = action.apply(state);
				listener.nodeFinished(runId, node, update == null ? Map.of() : update);
				return update == null ? Map.of() : update;
			}
			catch (Exception | Error e) {
				listener.nodeFailed(runId, node, e);
				throw e;
			}
		};
	}

	/** YAML's START and END are LangGraph4j's own constants. */
	static String id(String node) {
		return switch (node) {
			case GraphDefinition.START -> StateGraph.START;
			case GraphDefinition.END -> StateGraph.END;
			default -> node;
		};
	}
}

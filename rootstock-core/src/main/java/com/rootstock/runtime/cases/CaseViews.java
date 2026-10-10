package com.rootstock.runtime.cases;

import com.rootstock.core.cases.CaseRun;
import com.rootstock.core.cases.RunEvent;
import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.GraphDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** What the Cases screen sends and gets back. */
final class CaseViews {

	private CaseViews() {
	}

	/**
	 * @param input    the visit notes (or whatever the pack's graph takes)
	 * @param pack     whose graph to run; empty for the only one configured
	 * @param simulate for the stub nodes only: {@code clarify} or {@code chase} to drive those routes
	 */
	record SubmitRequest(@NotBlank @Size(max = 20_000) String input, String pack,
			@Pattern(regexp = "^(none|clarify|chase)?$") String simulate) {
	}

	record PauseView(String node, boolean before) {
	}

	/**
	 * @param events and {@code result} only in a single case's detail
	 * @param traceUrl the run's Langfuse trace; null when tracing is off
	 */
	record RunView(String caseId, String runId, String pack, String graph, String graphVersion, CaseRun.Status status,
			PauseView pause, String error, Instant startedAt, String traceUrl, List<RunEvent> events,
			Map<String, Object> result) {
	}

	static RunView summary(CaseRun run, String traceUrl) {
		return view(run, traceUrl, null, null);
	}

	static RunView detail(CaseRun run, String traceUrl) {
		return view(run, traceUrl, run.events(), run.result());
	}

	private static RunView view(CaseRun run, String traceUrl, List<RunEvent> events, Map<String, Object> result) {
		CaseRun.Pause pause = run.pause();
		return new RunView(run.caseId(), run.runId(), run.pack(), run.graph(), run.graphVersion(), run.status(),
				pause == null ? null : new PauseView(pause.node(), pause.before()), run.error(), run.startedAt(),
				traceUrl, events, result);
	}

	/** The graph's shape, for drawing it. */
	record GraphView(String pack, String name, String version, String description, List<NodeView> nodes,
			List<EdgeView> edges, List<String> interruptBefore, List<String> interruptAfter) {
	}

	/** @param kind the node type, or the agent's type for an agent node */
	record NodeView(String id, String kind, String agent, String description) {
	}

	/** @param label when this branch is taken, in words; null for a plain edge */
	record BranchView(String to, String label) {
	}

	record EdgeView(String from, List<BranchView> branches) {
	}

	static GraphView graph(CaseGraph graph) {
		GraphDefinition g = graph.definition().graph();
		List<NodeView> nodes = g.nodes().stream().map(n -> new NodeView(n.id(),
				n.agent() == null ? n.type() : graph.definition().agents().get(n.agent()).spec().type(), n.agent(),
				n.description())).toList();
		List<EdgeView> edges = g.edges().stream().map(CaseViews::edge).toList();
		return new GraphView(graph.pack(), g.metadata().name(), g.metadata().version(), g.metadata().description(),
				nodes, edges, g.runtime().interruptBefore(), g.runtime().interruptAfter());
	}

	private static EdgeView edge(GraphDefinition.EdgeSpec e) {
		if (e.router() != null) {
			return new EdgeView(e.from(), e.targets().stream()
					.map(t -> new BranchView(t, "router " + e.router())).toList());
		}
		if (e.route().isEmpty()) {
			return new EdgeView(e.from(), List.of(new BranchView(e.to(), null)));
		}
		return new EdgeView(e.from(), e.route().stream().map(r -> new BranchView(r.target(), r.isDefault() ? "otherwise"
				: r.when().entrySet().stream().map(c -> c.getKey() + " = " + c.getValue())
						.collect(Collectors.joining(" and ")))).toList());
	}
}

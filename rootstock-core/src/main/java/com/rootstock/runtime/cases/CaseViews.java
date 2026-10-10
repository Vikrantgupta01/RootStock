package com.rootstock.runtime.cases;

import com.rootstock.core.cases.CaseDecisions;
import com.rootstock.core.cases.CaseRun;
import com.rootstock.core.cases.RunEvent;
import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.GraphDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
	 * @param caseStatus how the case stands: its latest run's outcome (e.g. AWAITING_DECISION), or the run's status
	 * @param waitingFor when the case waits for a human decision: the roles that may make it; null otherwise
	 * @param input, {@code events} and {@code result} only in a single case's detail; input as submitted
	 * @param traceUrl the run's Langfuse trace; null when tracing is off
	 */
	record RunView(String caseId, String runId, String pack, String graph, String graphVersion, CaseRun.Status status,
			String caseStatus, PauseView pause, List<String> waitingFor, String error, Instant startedAt, String traceUrl, String input,
			List<RunEvent> events, Map<String, Object> result) {
	}

	/**
	 * @param decision APPROVED, EDITED or REJECTED
	 * @param record   the reviewer's version of the record, for EDITED
	 */
	record DecisionRequest(@NotNull CaseDecisions.Decision decision, @Size(max = 2_000) String comment,
			Map<String, Object> record) {
	}

	static RunView summary(CaseRun run, String traceUrl, List<String> waitingFor, String caseStatus) {
		return view(run, traceUrl, waitingFor, caseStatus, null, null, null);
	}

	static RunView detail(CaseRun run, String traceUrl, List<String> waitingFor, String caseStatus) {
		return view(run, traceUrl, waitingFor, caseStatus, run.input(), run.events(), run.result());
	}

	private static RunView view(CaseRun run, String traceUrl, List<String> waitingFor, String caseStatus, String input,
			List<RunEvent> events, Map<String, Object> result) {
		CaseRun.Pause pause = run.pause();
		return new RunView(run.caseId(), run.runId(), run.pack(), run.graph(), run.graphVersion(), run.status(),
				caseStatus, pause == null ? null : new PauseView(pause.node(), pause.before()), waitingFor, run.error(),
				run.startedAt(), traceUrl, input, events, result);
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

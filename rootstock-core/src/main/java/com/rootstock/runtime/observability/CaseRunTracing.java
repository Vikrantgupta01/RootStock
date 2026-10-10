package com.rootstock.runtime.observability;

import com.rootstock.core.cases.CaseGraphs;
import com.rootstock.core.cases.CaseRun;
import com.rootstock.core.cases.RunObserver;
import com.rootstock.core.graph.GraphDefinition;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Traces each case run to Langfuse: one {@code process-case} trace per run, with
 * the case id as the session (so a case that pauses and resumes later reads as
 * one story), and a {@code node-<id>} span per node. While a node runs its span
 * is current on that thread, so model and tool calls inside it nest under it.
 *
 * <p>The submitted text is not put on the trace, only its length: it is a
 * member's notes about a household. Masking that respects the ontology's pii
 * flags comes later (Iteration 13).
 */
@Component
public class CaseRunTracing implements RunObserver {

	private record Open(Observation observation, Observation.Scope scope) {
	}

	private final RequestTrace trace;
	private final ObservationRegistry registry;
	private final ObjectProvider<Tracer> tracer;
	private final ObjectProvider<CaseGraphs> graphs;
	private final Map<String, Observation> runs = new ConcurrentHashMap<>();
	private final Map<String, Open> nodes = new ConcurrentHashMap<>();

	public CaseRunTracing(RequestTrace trace, ObservationRegistry registry, ObjectProvider<Tracer> tracer,
			ObjectProvider<CaseGraphs> graphs) {
		this.trace = trace;
		this.registry = registry;
		this.tracer = tracer;
		this.graphs = graphs;
	}

	@Override
	public void runStarted(CaseRun run, String input) {
		Observation root = trace.start("process-case", RequestTrace.SURFACE_CASES, null);
		RequestTrace.session(root, run.caseId());
		RequestTrace.metadata(root, "pack", run.pack());
		RequestTrace.metadata(root, "graph", run.graph() + " " + run.graphVersion());
		RequestTrace.metadata(root, "caseId", run.caseId());
		RequestTrace.metadata(root, "runId", run.runId());
		RequestTrace.input(root, "Input of " + (input == null ? 0 : input.length()) + " characters");
		runs.put(run.runId(), root);
		Tracer t = tracer.getIfAvailable();
		if (t != null) {
			try (Observation.Scope ignored = root.openScope()) {
				Span span = t.currentSpan();
				if (span != null) {
					run.traceId(span.context().traceId());
				}
			}
		}
	}

	@Override
	public void nodeStarted(CaseRun run, String node) {
		Observation parent = runs.get(run.runId());
		if (parent == null) {
			return;
		}
		Observation observation = Observation.createNotStarted("node-" + node, registry).parentObservation(parent);
		RequestTrace.spanMetadata(observation, "node", node);
		GraphDefinition.NodeSpec spec = graphs.getObject().forPack(run.pack())
				.map(g -> g.definition().graph().node(node)).orElse(null);
		if (spec != null && spec.agent() != null) {
			RequestTrace.type(observation, LangfuseAttributes.TYPE_AGENT);
			RequestTrace.spanMetadata(observation, "agent", spec.agent());
		}
		else if (spec != null) {
			RequestTrace.spanMetadata(observation, "nodeType", spec.type());
		}
		observation.start();
		nodes.put(key(run, node), new Open(observation, observation.openScope()));
	}

	@Override
	public void nodeFinished(CaseRun run, String node, Map<String, Object> update) {
		Open open = nodes.remove(key(run, node));
		if (open != null) {
			RequestTrace.output(open.observation(), "Updated " + update.keySet().stream().sorted().toList());
			close(open);
		}
	}

	@Override
	public void nodeFailed(CaseRun run, String node, Throwable failure) {
		Open open = nodes.remove(key(run, node));
		if (open != null) {
			RequestTrace.level(open.observation(), LangfuseAttributes.LEVEL_ERROR, String.valueOf(failure.getMessage()));
			open.observation().error(failure);
			close(open);
		}
	}

	@Override
	public void runEnded(CaseRun run) {
		Observation root = runs.remove(run.runId());
		if (root == null) {
			return;
		}
		switch (run.status()) {
			case PAUSED -> RequestTrace.output(root, "Paused " + (run.pause().before() ? "before " : "after ")
					+ run.pause().node());
			case COMPLETED -> RequestTrace.output(root, "Completed");
			case FAILED -> {
				RequestTrace.output(root, "Failed");
				RequestTrace.level(root, LangfuseAttributes.LEVEL_ERROR, run.error());
			}
			case PARKED -> {
				RequestTrace.output(root, "Parked for a person");
				RequestTrace.level(root, LangfuseAttributes.LEVEL_WARNING, run.error());
			}
			case RUNNING -> {
			}
		}
		RequestTrace.metadata(root, "status", run.status().name());
		root.stop();
	}

	private static void close(Open open) {
		open.scope().close();
		open.observation().stop();
	}

	private static String key(CaseRun run, String node) {
		return run.runId() + "/" + node;
	}
}

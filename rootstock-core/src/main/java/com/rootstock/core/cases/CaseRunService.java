package com.rootstock.core.cases;

import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a case: creates its run, hands it to a worker thread, and returns at
 * once so the screen can follow the run's events. The run goes node by node
 * until the graph pauses it (e.g. before human review), it reaches END, or a
 * node fails.
 */
public final class CaseRunService implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(CaseRunService.class);

	private final CaseGraphs graphs;
	private final RunRegistry runs;
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

	public CaseRunService(CaseGraphs graphs, RunRegistry runs) {
		this.graphs = graphs;
		this.runs = runs;
	}

	/**
	 * @param pack      the pack whose graph to use; null for the only one configured
	 * @param input     what was submitted, e.g. visit notes
	 * @param options   run options, e.g. {@code simulate} for the stub nodes
	 * @param startedBy the submitting user's id
	 */
	public CaseRun start(String pack, String input, Map<String, Object> options, String startedBy) {
		CaseGraph graph = pack == null
				? graphs.only().orElseThrow(() -> new NoGraphException(graphs.all().isEmpty()
						? "No pack with a graph is configured (ROOTSTOCK_PACKS_PATHS)"
						: "Several packs have graphs; say which: " + graphs.all().stream().map(CaseGraph::pack).toList()))
				: graphs.forPack(pack).orElseThrow(() -> new NoGraphException("Pack '" + pack + "' has no graph"));

		CaseRun run = new CaseRun(UUID.randomUUID().toString(), UUID.randomUUID().toString(), graph.pack(),
				graph.name(), graph.version(), startedBy);
		runs.add(run);
		RunEvent started = run.started("Graph " + graph.name() + " " + graph.version() + " of pack " + graph.pack());
		runs.notify(o -> o.runStarted(run, input));
		run.publish(started);

		Map<String, Object> initial = new LinkedHashMap<>();
		initial.put(CaseState.CASE_ID, run.caseId());
		initial.put(CaseState.RUN_ID, run.runId());
		initial.put(CaseState.PACK, graph.pack());
		initial.put(CaseState.RAW_INPUT, input);
		initial.put(CaseState.OPTIONS, options == null ? Map.of() : Map.copyOf(options));
		workers.submit(() -> execute(graph, run, initial));
		return run;
	}

	private void execute(CaseGraph graph, CaseRun run, Map<String, Object> initial) {
		RunnableConfig config = RunnableConfig.builder().threadId(run.runId()).build();
		CaseState last = null;
		String lastNode = null;
		try {
			for (NodeOutput<CaseState> output : graph.compiled().stream(initial, config)) {
				last = output.state();
				if (!output.isSTART() && !output.isEND()) {
					lastNode = output.node();
				}
			}
			StateSnapshot<CaseState> snapshot = graph.compiled().getState(config);
			String next = snapshot == null ? null : snapshot.nextNodeId();
			Map<String, Object> result = result(snapshot != null ? snapshot.state() : last);
			RunEvent end;
			if (next == null || next.equals(StateGraph.END)) {
				end = run.completed(result);
			}
			else if (lastNode != null && graph.definition().graph().runtime().interruptAfter().contains(lastNode)) {
				end = run.paused(new CaseRun.Pause(lastNode, false), result);
			}
			else {
				end = run.paused(new CaseRun.Pause(next, true), result);
			}
			runs.notify(o -> o.runEnded(run));
			run.publish(end);
		}
		catch (Exception | Error e) {
			log.warn("Run {} of case {} failed: {}", run.runId(), run.caseId(), e.toString());
			RunEvent end = run.failed(reason(e), result(last));
			runs.notify(o -> o.runEnded(run));
			run.publish(end);
		}
	}

	/** The channels a screen may show once the run stops; the engine's own bookkeeping left out. */
	private static Map<String, Object> result(CaseState state) {
		if (state == null) {
			return Map.of();
		}
		Map<String, Object> out = new LinkedHashMap<>(state.data());
		List.of(CaseState.RUN_ID, CaseState.PACK, CaseState.RAW_INPUT, CaseState.OPTIONS).forEach(out::remove);
		return out;
	}

	private static String reason(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
	}

	@Override
	public void close() {
		workers.shutdownNow();
	}
}

package com.rootstock.core.cases;

import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseParkedException;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.GraphDefinition;
import com.rootstock.core.graph.RouteConditions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bsc.langgraph4j.GraphInput;
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
	private final CaseStore cases;
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

	public CaseRunService(CaseGraphs graphs, RunRegistry runs, CaseStore cases) {
		this.graphs = graphs;
		this.runs = runs;
		this.cases = cases;
	}

	/** Case files in memory only. */
	public CaseRunService(CaseGraphs graphs, RunRegistry runs) {
		this(graphs, runs, CaseStore.inMemory());
	}

	public Optional<CaseFile> caseFile(String caseId) {
		return cases.byId(caseId);
	}

	/** Cases that go on with a graph started by someone, e.g. waiting for a coordinator's decision. */
	public List<CaseFile> waiting() {
		return cases.waiting();
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
				graph.name(), graph.version(), startedBy, input);
		Instant now = Instant.now();
		cases.save(new CaseFile(run.caseId(), graph.pack(), CaseFile.RUNNING, null, List.of(), startedBy, input, now, now,
				run.runId(), Map.of()));
		runs.add(run);
		RunEvent started = run.started("Graph " + graph.name() + " " + graph.version() + " of pack " + graph.pack());
		runs.notify(o -> o.runStarted(run, input));
		run.publish(started);

		Map<String, Object> initial = new LinkedHashMap<>();
		initial.put(CaseState.CASE_ID, run.caseId());
		initial.put(CaseState.RUN_ID, run.runId());
		initial.put(CaseState.PACK, graph.pack());
		if (run.traceId() != null) {
			initial.put(CaseState.TRACE_ID, run.traceId());
		}
		initial.put(CaseState.RAW_INPUT, input);
		initial.put(CaseState.OPTIONS, options == null ? Map.of() : Map.copyOf(options));
		workers.submit(() -> execute(graph, run, initial));
		return run;
	}

	/**
	 * Starts the graph a case goes on with (its {@code next}), carrying the
	 * case's channels into the new run with {@code values} on top, e.g. a
	 * coordinator's decision. The new run is the case's latest; it returns at once
	 * and goes on in the background.
	 *
	 * @param startedBy who started it, e.g. the deciding coordinator's id
	 * @throws IllegalStateException when the case has nothing next, or a run of it is going
	 */
	public CaseRun startNext(String caseId, Map<String, Object> values, String note, String startedBy) {
		CaseFile file = cases.byId(caseId).orElseThrow(() -> new NoSuchElementException("No case " + caseId));
		CaseRun run;
		CaseGraph graph;
		synchronized (this) {
			file = cases.byId(caseId).orElseThrow();
			if (file.next() == null || CaseFile.RUNNING.equals(file.status())) {
				throw new IllegalStateException("Case " + caseId + " is " + file.status() + ", with nothing to start");
			}
			String next = file.next();
			graph = graphs.graph(file.pack(), next)
					.orElseThrow(() -> new NoGraphException("Pack has no graph '" + next + "'"));
			run = new CaseRun(caseId, UUID.randomUUID().toString(), graph.pack(), graph.name(), graph.version(),
					startedBy, file.input());
			cases.save(file.with(CaseFile.RUNNING, null, List.of(), run.runId(), file.state(), Instant.now()));
		}
		runs.add(run);
		RunEvent started = run.started("Graph " + graph.name() + " " + graph.version() + " of pack " + graph.pack()
				+ (note == null ? "" : ": " + note));
		CaseRun begun = run;
		String input = file.input();
		runs.notify(o -> o.runStarted(begun, input));
		run.publish(started);

		Map<String, Object> initial = new LinkedHashMap<>(file.state());
		initial.put(CaseState.CASE_ID, caseId);
		initial.put(CaseState.RUN_ID, run.runId());
		initial.put(CaseState.PACK, graph.pack());
		if (run.traceId() != null) {
			initial.put(CaseState.TRACE_ID, run.traceId());
		}
		initial.putIfAbsent(CaseState.OPTIONS, Map.of());
		initial.putAll(values);
		CaseGraph g = graph;
		workers.submit(() -> execute(g, begun, initial));
		return run;
	}

	/**
	 * Carries on a paused run, e.g. once a reviewer has decided: {@code values}
	 * are written into its state (such as {@code review}, or an edited record),
	 * then the graph goes on from where it stopped. Returns at once; the run goes
	 * on in the background like a new one.
	 *
	 * @param note what resumed it, for the run's events and trace
	 * @throws IllegalStateException when the run is not paused
	 */
	public CaseRun resume(String caseId, Map<String, Object> values, String note) {
		CaseRun run = runs.byCase(caseId).orElseThrow(() -> new NoSuchElementException("No case " + caseId));
		CaseGraph graph = graphs.forPack(run.pack())
				.orElseThrow(() -> new NoGraphException("Pack '" + run.pack() + "' has no graph"));
		synchronized (run) {
			if (run.status() != CaseRun.Status.PAUSED) {
				throw new IllegalStateException("Case " + caseId + " is " + run.status() + ", not paused");
			}
			if (values.get(CaseState.REVIEW) instanceof Map<?, ?> review) {
				@SuppressWarnings("unchecked")
				Map<String, Object> decision = (Map<String, Object>) review;
				run.decided(CaseState.REVIEW, decision);
			}
			RunEvent resumed = run.resumed(note);
			runs.save(run);
			run.publish(resumed);
		}
		runs.notify(o -> o.runResumed(run, note));
		workers.submit(() -> drive(graph, run, config -> {
			RunnableConfig updated = values.isEmpty() ? config : graph.compiled().updateState(config, values);
			return graph.compiled().stream(GraphInput.resume(), updated);
		}));
		return run;
	}

	private void execute(CaseGraph graph, CaseRun run, Map<String, Object> initial) {
		drive(graph, run, config -> graph.compiled().stream(initial, config));
	}

	private interface Steps {
		Iterable<NodeOutput<CaseState>> from(RunnableConfig config) throws Exception;
	}

	/** Runs the graph until it pauses, reaches END, fails or is parked, and records how it stopped. */
	private void drive(CaseGraph graph, CaseRun run, Steps steps) {
		RunnableConfig config = RunnableConfig.builder().threadId(run.runId()).build();
		CaseState last = null;
		String lastNode = null;
		boolean reachedEnd = false;
		try {
			for (NodeOutput<CaseState> output : steps.from(config)) {
				last = output.state();
				reachedEnd |= output.isEND();
				if (!output.isSTART() && !output.isEND()) {
					lastNode = output.node();
				}
			}
			// A finished thread has nothing more to read; a paused one says where it stopped.
			StateSnapshot<CaseState> snapshot = reachedEnd ? null : graph.compiled().getState(config);
			String next = snapshot == null ? null : snapshot.nextNodeId();
			Map<String, Object> result = result(snapshot != null ? snapshot.state() : last);
			boolean completed = reachedEnd || next == null || next.equals(StateGraph.END);
			// The case first, then the run: whoever sees the run end then finds the case as it now stands.
			fileOutcome(graph, run, completed ? CaseRun.Status.COMPLETED : CaseRun.Status.PAUSED, lastNode,
					snapshot != null ? snapshot.state() : last);
			RunEvent end;
			if (completed) {
				end = run.completed(result);
			}
			else if (lastNode != null && graph.definition().graph().runtime().interruptAfter().contains(lastNode)) {
				end = run.paused(new CaseRun.Pause(lastNode, false), result);
			}
			else {
				end = run.paused(new CaseRun.Pause(next, true), result);
			}
			runs.save(run);
			runs.notify(o -> o.runEnded(run));
			run.publish(end);
		}
		catch (Exception | Error e) {
			CaseParkedException parked = parked(e);
			fileOutcome(graph, run, parked != null ? CaseRun.Status.PARKED : CaseRun.Status.FAILED, lastNode, last);
			RunEvent end;
			if (parked != null) {
				log.info("Run {} of case {} parked: {}", run.runId(), run.caseId(), parked.getMessage());
				end = run.parked(parked.getMessage(), result(last));
			}
			else {
				log.warn("Run {} of case {} failed: {}", run.runId(), run.caseId(), e.toString());
				end = run.failed(reason(e), result(last));
			}
			runs.save(run);
			runs.notify(o -> o.runEnded(run));
			run.publish(end);
		}
	}

	/**
	 * Records on the case how its run ended: the graph's outcome for the node it
	 * ended after (status, and the graph that comes next), or the run's own end;
	 * and the channels it carries into its next run.
	 */
	private void fileOutcome(CaseGraph graph, CaseRun run, CaseRun.Status ended, String lastNode, CaseState state) {
		try {
			CaseFile file = cases.byId(run.caseId()).orElse(null);
			if (file == null) {
				return;
			}
			String status = ended.name();
			String next = null;
			List<String> waitingFor = List.of();
			if (ended == CaseRun.Status.COMPLETED && lastNode != null && state != null) {
				Optional<GraphDefinition.Outcome> outcome = RouteConditions.outcome(graph.definition().graph(), lastNode,
						state);
				if (outcome.isPresent()) {
					status = outcome.get().status();
					next = outcome.get().next();
					waitingFor = next == null ? List.of() : graphs.graph(run.pack(), next)
							.map(g -> g.definition().graph().trigger().approverRoles()).orElse(List.of());
				}
			}
			Map<String, Object> carried = new LinkedHashMap<>(state == null ? file.state() : state.data());
			List.of(CaseState.RUN_ID, CaseState.TRACE_ID).forEach(carried::remove);
			cases.save(file.with(status, next, waitingFor, run.runId(), carried, Instant.now()));
		}
		catch (RuntimeException e) {
			log.warn("Could not record how run {} of case {} ended: {}", run.runId(), run.caseId(), e.toString());
		}
	}

	/** The channels a screen may show once the run stops; the engine's own bookkeeping left out. */
	private static Map<String, Object> result(CaseState state) {
		if (state == null) {
			return Map.of();
		}
		Map<String, Object> out = new LinkedHashMap<>(state.data());
		List.of(CaseState.RUN_ID, CaseState.PACK, CaseState.TRACE_ID, CaseState.RAW_INPUT, CaseState.OPTIONS).forEach(out::remove);
		return out;
	}

	private static CaseParkedException parked(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof CaseParkedException p) {
				return p;
			}
		}
		return null;
	}

	private static String reason(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
	}

	/**
	 * Lets runs reach their next save before stopping: a run cut off while it
	 * saves its state would lose it. After ten seconds, the rest are interrupted
	 * (and marked FAILED at the next start).
	 */
	@Override
	public void close() {
		workers.shutdown();
		try {
			if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
				workers.shutdownNow();
			}
		}
		catch (InterruptedException e) {
			workers.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}

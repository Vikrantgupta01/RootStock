package com.rootstock.core.cases;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One run of a pack's graph for one case, kept in memory: its status, where it
 * paused, its events so far, and what its nodes produced. Thread-safe: the run
 * advances on a worker thread while screens read and subscribe to it.
 */
public final class CaseRun {

	public enum Status {
		RUNNING, PAUSED, COMPLETED, FAILED
	}

	/**
	 * Where a paused run stopped.
	 *
	 * @param before true when it stopped before {@code node} (e.g. review), false when after it (e.g. clarify)
	 */
	public record Pause(String node, boolean before) {
	}

	private final String caseId;
	private final String runId;
	private final String pack;
	private final String graph;
	private final String graphVersion;
	private final String startedBy;
	private final Instant startedAt = Instant.now();
	private final List<RunEvent> events = new ArrayList<>();
	private final List<Consumer<RunEvent>> subscribers = new CopyOnWriteArrayList<>();
	private final Map<String, Instant> nodeStarts = new HashMap<>();
	private Status status = Status.RUNNING;
	private Pause pause;
	private String error;
	private String traceId;
	private Map<String, Object> result = Map.of();

	CaseRun(String caseId, String runId, String pack, String graph, String graphVersion, String startedBy) {
		this.caseId = caseId;
		this.runId = runId;
		this.pack = pack;
		this.graph = graph;
		this.graphVersion = graphVersion;
		this.startedBy = startedBy;
	}

	public String caseId() {
		return caseId;
	}

	public String runId() {
		return runId;
	}

	public String pack() {
		return pack;
	}

	public String graph() {
		return graph;
	}

	public String graphVersion() {
		return graphVersion;
	}

	/** The user who submitted the case (their id, not their email). */
	public String startedBy() {
		return startedBy;
	}

	public Instant startedAt() {
		return startedAt;
	}

	public synchronized Status status() {
		return status;
	}

	public synchronized Pause pause() {
		return pause;
	}

	public synchronized String error() {
		return error;
	}

	public synchronized String traceId() {
		return traceId;
	}

	/** Set by tracing when the run's trace starts. */
	public synchronized void traceId(String traceId) {
		this.traceId = traceId;
	}

	/** What the run's channels held when it stopped (record, issues, actions, audit…). */
	public synchronized Map<String, Object> result() {
		return result;
	}

	public synchronized List<RunEvent> events() {
		return List.copyOf(events);
	}

	/**
	 * Calls {@code listener} with every event so far, then each new one as it
	 * happens. Returns a handle that stops it.
	 */
	public Runnable subscribe(Consumer<RunEvent> listener) {
		List<RunEvent> past;
		synchronized (this) {
			past = List.copyOf(events);
			subscribers.add(listener);
		}
		past.forEach(listener);
		return () -> subscribers.remove(listener);
	}

	synchronized RunEvent nodeStarted(String node) {
		nodeStarts.put(node, Instant.now());
		return add(RunEvent.Type.NODE_STARTED, node, null, null);
	}

	synchronized RunEvent nodeEnded(String node, boolean ok, String detail) {
		Instant began = nodeStarts.remove(node);
		Long ms = began == null ? null : Duration.between(began, Instant.now()).toMillis();
		return add(ok ? RunEvent.Type.NODE_FINISHED : RunEvent.Type.NODE_FAILED, node, ms, detail);
	}

	synchronized RunEvent started(String detail) {
		return add(RunEvent.Type.RUN_STARTED, null, null, detail);
	}

	synchronized RunEvent paused(Pause pause, Map<String, Object> result) {
		this.status = Status.PAUSED;
		this.pause = pause;
		this.result = result;
		return add(RunEvent.Type.RUN_PAUSED, pause.node(), null,
				(pause.before() ? "Waiting before " : "Waiting after ") + pause.node());
	}

	synchronized RunEvent completed(Map<String, Object> result) {
		this.status = Status.COMPLETED;
		this.result = result;
		return add(RunEvent.Type.RUN_COMPLETED, null, null, "The run reached END");
	}

	synchronized RunEvent failed(String error, Map<String, Object> result) {
		this.status = Status.FAILED;
		this.error = error;
		this.result = result;
		return add(RunEvent.Type.RUN_FAILED, null, null, error);
	}

	private RunEvent add(RunEvent.Type type, String node, Long ms, String detail) {
		RunEvent event = new RunEvent(events.size() + 1, type, node, Instant.now(), ms, detail);
		events.add(event);
		return event;
	}

	/** Tells subscribers, outside the lock, so a slow screen never holds up the run. */
	void publish(RunEvent event) {
		for (Consumer<RunEvent> s : subscribers) {
			try {
				s.accept(event);
			}
			catch (RuntimeException ignored) {
				// A subscriber that fails (a closed connection) is its own problem.
			}
		}
	}
}

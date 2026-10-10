package com.rootstock.core.cases;

import com.rootstock.core.graph.NodeListener;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runs Rootstock knows about and the bridge from graph nodes to each run's
 * events and observers. Runs being worked on, and the most recent
 * {@link #CAPACITY}, are held in memory; every change is saved to the
 * {@link RunStore}, so after a restart a run is found there, paused runs
 * included.
 */
public final class RunRegistry implements NodeListener {

	static final int CAPACITY = 200;

	private static final Logger log = LoggerFactory.getLogger(RunRegistry.class);

	private final List<RunObserver> observers;
	private final RunStore store;
	private final Map<String, CaseRun> byCase = new LinkedHashMap<>(16, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CaseRun> eldest) {
			return size() > CAPACITY;
		}
	};
	private final Map<String, CaseRun> byRun = new java.util.concurrent.ConcurrentHashMap<>();

	public RunRegistry(Collection<? extends RunObserver> observers, RunStore store) {
		this.observers = List.copyOf(observers);
		this.store = store;
	}

	/** In memory only. */
	public RunRegistry(Collection<? extends RunObserver> observers) {
		this(observers, RunStore.NONE);
	}

	synchronized void add(CaseRun run) {
		hold(run);
		save(run);
	}

	private void hold(CaseRun run) {
		byCase.put(run.caseId(), run);
		byRun.put(run.runId(), run);
		byRun.keySet().retainAll(byCase.values().stream().map(CaseRun::runId).toList());
	}

	/** Saves the run as it is now. A store that fails is logged: the run itself carries on. */
	void save(CaseRun run) {
		try {
			store.save(run);
		}
		catch (RuntimeException e) {
			log.warn("Could not save run {} of case {}: {}", run.runId(), run.caseId(), e.toString());
		}
	}

	/** In memory, or as saved (e.g. before a restart). */
	public synchronized Optional<CaseRun> byCase(String caseId) {
		CaseRun run = byCase.get(caseId);
		if (run == null) {
			run = store.byCase(caseId).orElse(null);
			if (run != null) {
				hold(run);
			}
		}
		return Optional.ofNullable(run);
	}

	/** Newest first: the saved runs, with the ones in memory as they are now. */
	public synchronized List<CaseRun> recent() {
		Map<String, CaseRun> all = new LinkedHashMap<>();
		store.recent(CAPACITY).forEach(r -> all.put(r.caseId(), r));
		all.putAll(byCase);
		return all.values().stream().sorted((a, b) -> b.startedAt().compareTo(a.startedAt())).limit(CAPACITY).toList();
	}

	/** Every paused run: the saved ones, with the ones in memory as they are now. */
	public synchronized List<CaseRun> paused() {
		Map<String, CaseRun> all = new LinkedHashMap<>();
		store.paused().forEach(r -> all.put(r.caseId(), byCase.getOrDefault(r.caseId(), r)));
		byCase.values().stream().filter(r -> r.status() == CaseRun.Status.PAUSED).forEach(r -> all.put(r.caseId(), r));
		return all.values().stream().filter(r -> r.status() == CaseRun.Status.PAUSED).toList();
	}

	/**
	 * Runs saved as running when Rootstock starts were cut off by the stop: no
	 * one is running them, and they cannot carry on mid-node. They become
	 * FAILED, saying so. Paused runs are untouched: they resume from their
	 * checkpoints.
	 */
	public int failInterrupted() {
		List<CaseRun> stale = store.running();
		for (CaseRun run : stale) {
			run.interrupted("Rootstock stopped while this run was in progress; submit the case again");
			save(run);
		}
		return stale.size();
	}

	void notify(Consumer<RunObserver> call) {
		for (RunObserver o : observers) {
			try {
				call.accept(o);
			}
			catch (RuntimeException e) {
				log.warn("Run observer {} failed: {}", o.getClass().getSimpleName(), e.toString());
			}
		}
	}

	@Override
	public void nodeStarted(String runId, String node) {
		CaseRun run = byRun.get(runId);
		if (run != null) {
			RunEvent event = run.nodeStarted(node);
			save(run);
			run.publish(event);
			notify(o -> o.nodeStarted(run, node));
		}
	}

	@Override
	public void nodeFinished(String runId, String node, Map<String, Object> update) {
		CaseRun run = byRun.get(runId);
		if (run != null) {
			RunEvent event = run.nodeEnded(node, true, Summaries.of(update));
			save(run);
			run.publish(event);
			notify(o -> o.nodeFinished(run, node, update));
		}
	}

	@Override
	public void nodeFailed(String runId, String node, Throwable failure) {
		CaseRun run = byRun.get(runId);
		if (run != null) {
			RunEvent event = run.nodeEnded(node, false, String.valueOf(failure.getMessage()));
			save(run);
			run.publish(event);
			notify(o -> o.nodeFailed(run, node, failure));
		}
	}
}

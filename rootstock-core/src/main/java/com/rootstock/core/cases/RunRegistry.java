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
 * The runs Rootstock knows about, in memory, and the bridge from graph nodes to
 * each run's events and observers. Keeps the most recent {@link #CAPACITY} runs;
 * nothing survives a restart yet.
 */
public final class RunRegistry implements NodeListener {

	static final int CAPACITY = 200;

	private static final Logger log = LoggerFactory.getLogger(RunRegistry.class);

	private final List<RunObserver> observers;
	private final Map<String, CaseRun> byCase = new LinkedHashMap<>(16, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CaseRun> eldest) {
			return size() > CAPACITY;
		}
	};
	private final Map<String, CaseRun> byRun = new java.util.concurrent.ConcurrentHashMap<>();

	public RunRegistry(Collection<? extends RunObserver> observers) {
		this.observers = List.copyOf(observers);
	}

	synchronized void add(CaseRun run) {
		byCase.put(run.caseId(), run);
		byRun.put(run.runId(), run);
		byRun.keySet().retainAll(byCase.values().stream().map(CaseRun::runId).toList());
	}

	public synchronized Optional<CaseRun> byCase(String caseId) {
		return Optional.ofNullable(byCase.get(caseId));
	}

	/** Newest first. */
	public synchronized List<CaseRun> recent() {
		return byCase.values().stream().sorted((a, b) -> b.startedAt().compareTo(a.startedAt())).toList();
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
			run.publish(run.nodeStarted(node));
			notify(o -> o.nodeStarted(run, node));
		}
	}

	@Override
	public void nodeFinished(String runId, String node, Map<String, Object> update) {
		CaseRun run = byRun.get(runId);
		if (run != null) {
			run.publish(run.nodeEnded(node, true, Summaries.of(update)));
			notify(o -> o.nodeFinished(run, node, update));
		}
	}

	@Override
	public void nodeFailed(String runId, String node, Throwable failure) {
		CaseRun run = byRun.get(runId);
		if (run != null) {
			run.publish(run.nodeEnded(node, false, String.valueOf(failure.getMessage())));
			notify(o -> o.nodeFailed(run, node, failure));
		}
	}
}

package com.rootstock.core.cases;

import java.util.Map;

/**
 * Hears about every run, e.g. to trace it. {@link #runStarted} is called on the
 * thread that submitted the case (so it can read who submitted it); the rest on
 * the thread running the graph. Must not throw.
 */
public interface RunObserver {

	void runStarted(CaseRun run, String input);

	/**
	 * A paused run carries on, e.g. after a review; called on the thread that
	 * resumed it.
	 *
	 * @param note what resumed it, e.g. "Approved by …"
	 */
	default void runResumed(CaseRun run, String note) {
	}

	void nodeStarted(CaseRun run, String node);

	void nodeFinished(CaseRun run, String node, Map<String, Object> update);

	void nodeFailed(CaseRun run, String node, Throwable failure);

	/** The run paused, completed, failed or was parked; see its status. */
	void runEnded(CaseRun run);
}

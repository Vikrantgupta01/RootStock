package com.rootstock.core.graph;

import java.util.Map;

/**
 * Told when each node of a run starts and ends, e.g. to stream progress to the
 * screen and to trace each node as a span. Called on the thread running the
 * node; must not throw.
 */
public interface NodeListener {

	void nodeStarted(String runId, String node);

	/** @param update the channels the node changed */
	void nodeFinished(String runId, String node, Map<String, Object> update);

	void nodeFailed(String runId, String node, Throwable failure);

	NodeListener NONE = new NodeListener() {

		@Override
		public void nodeStarted(String runId, String node) {
		}

		@Override
		public void nodeFinished(String runId, String node, Map<String, Object> update) {
		}

		@Override
		public void nodeFailed(String runId, String node, Throwable failure) {
		}
	};
}

package com.rootstock.core.cases;

import java.time.Instant;

/**
 * One step in a run's progress, as the screen shows it.
 *
 * @param seq        position in the run's events, from 1
 * @param node       the node it is about; null for run-level events
 * @param durationMs for NODE_FINISHED and NODE_FAILED, how long the node took
 * @param detail     a short human-readable note
 */
public record RunEvent(long seq, Type type, String node, Instant at, Long durationMs, String detail) {

	public enum Type {
		RUN_STARTED, RUN_RESUMED, NODE_STARTED, NODE_FINISHED, NODE_FAILED, RUN_PAUSED, RUN_COMPLETED, RUN_FAILED, RUN_PARKED;

		/** After one of these, nothing more happens until the run is resumed. */
		public boolean ends() {
			return this == RUN_PAUSED || this == RUN_COMPLETED || this == RUN_FAILED || this == RUN_PARKED;
		}
	}
}

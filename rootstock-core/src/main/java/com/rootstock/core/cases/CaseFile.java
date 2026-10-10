package com.rootstock.core.cases;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A case between its runs: how it stands, what comes next, and what it carries
 * into the next run. A case goes through several short runs, each of one of its
 * pack's graphs; no graph is held open while it waits.
 *
 * @param status     RUNNING while a run is going; otherwise the outcome of the last run, as its graph says
 *                   (e.g. AWAITING_DECISION, DONE), or the run's own end (COMPLETED, PAUSED, FAILED, PARKED)
 * @param next       the graph the case goes on with; null when it is finished or waiting inside a run
 * @param waitingFor the roles that may start {@code next} (its trigger's approverRoles)
 * @param state      the channels it carries into its next run (record, context, issues, actions, audit…)
 */
public record CaseFile(String caseId, String pack, String status, String next, List<String> waitingFor,
		String startedBy, String input, Instant createdAt, Instant updatedAt, String lastRunId,
		Map<String, Object> state) {

	public static final String RUNNING = "RUNNING";

	public CaseFile {
		waitingFor = waitingFor == null ? List.of() : List.copyOf(waitingFor);
		state = state == null ? Map.of() : state;
	}

	public CaseFile with(String status, String next, List<String> waitingFor, String lastRunId,
			Map<String, Object> state, Instant now) {
		return new CaseFile(caseId, pack, status, next, waitingFor, startedBy, input, createdAt, now, lastRunId, state);
	}
}

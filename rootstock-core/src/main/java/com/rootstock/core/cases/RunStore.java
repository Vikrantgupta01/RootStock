package com.rootstock.core.cases;

import java.util.List;
import java.util.Optional;

/**
 * Where runs are kept so they outlive Rootstock: their status, events and
 * result, for the screens. (The graph's state, for resuming, is in the
 * checkpoints.)
 */
public interface RunStore {

	/** Saves the run as it is now, replacing what was saved before. */
	void save(CaseRun run);

	/** The case's latest run. */
	Optional<CaseRun> byCase(String caseId);

	/** Each case's latest run, newest first. */
	List<CaseRun> recent(int limit);

	/** The runs saved as running, which cannot be: Rootstock was not running them. */
	List<CaseRun> running();

	/** Every paused run, however old. */
	List<CaseRun> paused();

	/** Keeps nothing: runs live only in memory. */
	RunStore NONE = new RunStore() {

		@Override
		public void save(CaseRun run) {
		}

		@Override
		public Optional<CaseRun> byCase(String caseId) {
			return Optional.empty();
		}

		@Override
		public List<CaseRun> recent(int limit) {
			return List.of();
		}

		@Override
		public List<CaseRun> running() {
			return List.of();
		}

		@Override
		public List<CaseRun> paused() {
			return List.of();
		}
	};
}

package com.rootstock.core.cases;

import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.UserRole;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.GraphDefinition;
import com.rootstock.core.graph.RouteConditions;
import com.rootstock.core.graph.nodes.HumanReviewNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Human decisions on cases, the engine's side of sign-off. The review screens
 * belong to the client's own application; who may decide is checked here.
 *
 * <p>A case waits for a decision in one of two ways:
 * <ul>
 * <li><b>Between runs</b> (preferred): its last run ended with an outcome whose
 * {@code next} graph is started by a decision. The decision starts that graph,
 * which re-checks the case against fresh data before anything is written: a
 * case can wait days, and the client's data may change meanwhile.</li>
 * <li><b>Inside a run</b>: the run is paused before a {@code human-review} node
 * and resumes with the decision.</li>
 * </ul>
 * Either way only someone in the {@code approverRoles} (Cognito groups) or an
 * admin may decide, the decision is put into the case's state as {@code review}
 * (with the reviewer's edited record, for an edit, and the issues they were
 * shown), and the human-review node records it in the audit.
 */
public final class CaseDecisions {

	public static final String APPROVER_ROLES = "approverRoles";

	public enum Decision {
		/** Go ahead as drafted. */
		APPROVED,
		/** Go ahead with the reviewer's changes to the record (checked again first). */
		EDITED,
		/** Do not go ahead. */
		REJECTED
	}

	/** Why a decision could not be made. */
	public static class DecisionException extends RuntimeException {

		public enum Reason {
			NOT_FOUND, FORBIDDEN, CONFLICT, INVALID
		}

		private final Reason reason;

		public DecisionException(Reason reason, String message) {
			super(message);
			this.reason = reason;
		}

		public Reason reason() {
			return reason;
		}
	}

	private final CaseRunService service;
	private final RunRegistry runs;
	private final CaseGraphs graphs;
	private final Clock clock;

	public CaseDecisions(CaseRunService service, RunRegistry runs, CaseGraphs graphs, Clock clock) {
		this.service = service;
		this.runs = runs;
		this.graphs = graphs;
		this.clock = clock;
	}

	/** The roles that may decide the case now; empty when it is not waiting for a decision. */
	public Optional<List<String>> waitingFor(String caseId) {
		Optional<CaseFile> file = service.caseFile(caseId);
		if (file.isPresent() && betweenRuns(file.get())) {
			return Optional.of(file.get().waitingFor());
		}
		return runs.byCase(caseId).flatMap(this::pausedAt).map(CaseDecisions::approverRoles);
	}

	/** Whether this person may decide the case now: it waits for a decision, and they are an approver or an admin. */
	public boolean mayDecide(String caseId, AuthContext.Principal user) {
		return waitingFor(caseId).map(roles -> user.role() == UserRole.ADMIN
				|| roles.stream().anyMatch(user.groups()::contains)).orElse(false);
	}

	/** The cases waiting for a decision this person may make (their latest runs), oldest first. */
	public List<CaseRun> awaiting(AuthContext.Principal user) {
		Map<String, CaseRun> out = new LinkedHashMap<>();
		for (CaseFile f : service.waiting()) {
			if (betweenRuns(f) && mayDecide(f.caseId(), user)) {
				runs.byCase(f.caseId()).ifPresent(r -> out.put(f.caseId(), r));
			}
		}
		for (CaseRun r : runs.paused()) {
			if (pausedAt(r).isPresent() && mayDecide(r.caseId(), user)) {
				out.putIfAbsent(r.caseId(), r);
			}
		}
		return out.values().stream().sorted((a, b) -> a.startedAt().compareTo(b.startedAt())).toList();
	}

	/**
	 * Records the decision and carries the case on with it: starts its next
	 * graph, or resumes its paused run.
	 *
	 * @param record the reviewer's version of the record; required for EDITED, ignored otherwise
	 * @return the run that goes on with the decision
	 */
	@SuppressWarnings("unchecked")
	public CaseRun decide(String caseId, Decision decision, Map<String, Object> record, String comment,
			AuthContext.Principal user) {
		Optional<CaseFile> file = service.caseFile(caseId);
		Optional<CaseRun> latest = runs.byCase(caseId);
		if (file.isEmpty() && latest.isEmpty()) {
			throw new DecisionException(DecisionException.Reason.NOT_FOUND, "No case " + caseId);
		}
		List<String> roles = waitingFor(caseId).orElseThrow(() -> new DecisionException(
				DecisionException.Reason.CONFLICT, "Case " + caseId + " is not waiting for a decision ("
						+ file.map(CaseFile::status).orElse(latest.map(r -> r.status().name()).orElse("?")) + ")"));
		if (!mayDecide(caseId, user)) {
			throw new DecisionException(DecisionException.Reason.FORBIDDEN, "Only " + roles
					+ " (or an admin) may decide this case");
		}
		if (decision == Decision.EDITED && (record == null || record.isEmpty())) {
			throw new DecisionException(DecisionException.Reason.INVALID, "An edit needs the edited record");
		}

		// What the reviewer was shown, so a re-check can tell what is new (issues.unseenBy: review).
		Map<String, Object> shownState = file.filter(this::betweenRuns).map(CaseFile::state)
				.orElse(latest.map(CaseRun::result).orElse(Map.of()));
		List<String> seen = shownState.get("issues") instanceof List<?> l
				? l.stream().map(RouteConditions::signature).toList() : List.of();
		String note = comment == null || comment.isBlank() ? null : comment.strip();
		// Plain maps of plain values: state is carried and checkpointed by serialization.
		Map<String, Object> review = new LinkedHashMap<>();
		review.put("decision", decision.name());
		review.put("by", user.userId());
		review.put("byEmail", user.email());
		review.put("comment", note);
		review.put("at", clock.instant().toString());
		review.put("seen", new ArrayList<>(seen));
		Map<String, Object> values = new LinkedHashMap<>();
		values.put(CaseState.REVIEW, review);
		if (decision == Decision.EDITED) {
			values.put("record", new LinkedHashMap<>(record));
		}
		String how = decision.name() + " by " + (user.email() == null ? user.userId() : user.email())
				+ (note == null ? "" : ": " + note);
		try {
			if (file.isPresent() && betweenRuns(file.get())) {
				return service.startNext(caseId, values, how, user.userId());
			}
			return service.resume(caseId, values, how);
		}
		catch (IllegalStateException e) {
			// Someone else decided between the check and now.
			throw new DecisionException(DecisionException.Reason.CONFLICT, e.getMessage());
		}
	}

	/** Waiting between runs for a graph a person's decision starts. */
	private boolean betweenRuns(CaseFile f) {
		return f.next() != null && !CaseFile.RUNNING.equals(f.status()) && graphs.graph(f.pack(), f.next())
				.map(g -> g.definition().graph().trigger().decision()).orElse(false);
	}

	/** The human-review node a run is paused before; empty when it is not paused at one. */
	private Optional<GraphDefinition.NodeSpec> pausedAt(CaseRun run) {
		CaseRun.Pause pause = run.pause();
		if (run.status() != CaseRun.Status.PAUSED || pause == null || !pause.before()) {
			return Optional.empty();
		}
		return graphs.graph(run.pack(), run.graph()).map(g -> g.definition().graph().node(pause.node()))
				.filter(n -> HumanReviewNode.TYPE.equals(n.type()));
	}

	/** The groups whose members may decide at this node. */
	static List<String> approverRoles(GraphDefinition.NodeSpec node) {
		Object roles = node.config().get(APPROVER_ROLES);
		return roles instanceof Collection<?> c ? c.stream().map(String::valueOf).toList() : List.of();
	}
}

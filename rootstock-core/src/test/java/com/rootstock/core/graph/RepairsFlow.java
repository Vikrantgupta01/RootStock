package com.rootstock.core.graph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The repairs pack the two-graph way: a {@code job-intake} graph that ends
 * after drafting, with the case waiting for a decision, and a
 * {@code job-decision} graph started by a property manager's decision, which
 * re-checks the job before it books anything.
 */
public final class RepairsFlow {

	static final String INTAKE = """
			apiVersion: rootstock/v1
			kind: Graph
			metadata: { name: job-intake, version: 1.0.0 }
			trigger: { kind: submit }
			state:
			  channels:
			    record: { reducer: replace }
			    context: { reducer: merge }
			    issues: { reducer: replace }
			    questions: { reducer: replace }
			    actions: { reducer: replace }
			    audit: { reducer: append }
			nodes:
			  - { id: ingest, type: ingest }
			  - { id: extract, agent: job-extractor }
			  - { id: enrich, agent: property-lookup }
			  - { id: validate, type: rules, config: { judge: job-judge } }
			  - { id: clarify, type: clarify, config: { questionWriter: question-writer } }
			  - { id: draft, agent: visit-planner, config: { actionType: VISIT } }
			  - { id: chase, agent: tenant-chaser, config: { actionType: CHASE_MESSAGE } }
			edges:
			  - { from: START, to: ingest }
			  - { from: ingest, to: extract }
			  - { from: extract, to: enrich }
			  - { from: enrich, to: validate }
			  - from: validate
			    route:
			      - { when: { issues.anySeverity: BLOCKING, issues.answerableBy: SUBMITTER }, to: clarify }
			      - { when: { issues.anySeverity: BLOCKING }, to: chase }
			      - { default: draft }
			  - { from: clarify, to: END }
			  - { from: draft, to: END }
			  - { from: chase, to: END }
			outcomes:
			  clarify: [{ status: AWAITING_SUBMITTER }]
			  draft: [{ status: AWAITING_DECISION, next: job-decision }]
			  chase: [{ status: AWAITING_DECISION, next: job-decision }]
			""";

	static final String DECISION = """
			apiVersion: rootstock/v1
			kind: Graph
			metadata: { name: job-decision, version: 1.0.0 }
			trigger: { kind: decision, approverRoles: [property-manager] }
			state:
			  channels:
			    record: { reducer: replace }
			    context: { reducer: merge }
			    issues: { reducer: replace }
			    questions: { reducer: replace }
			    actions: { reducer: replace }
			    audit: { reducer: append }
			nodes:
			  - { id: review, type: human-review }
			  - { id: enrich, agent: property-lookup }
			  - { id: validate, type: rules, config: { judge: job-judge } }
			  - { id: commit, type: tool-executor, config: { allowWrites: true } }
			edges:
			  - { from: START, to: review }
			  - from: review
			    route:
			      - { when: { review.decision: REJECTED }, to: END }
			      - { default: enrich }
			  - { from: enrich, to: validate }
			  - from: validate
			    route:
			      - { when: { issues.anySeverity: BLOCKING }, to: END }
			      - { when: { issues.unseenBy: review }, to: END }
			      - { default: commit }
			  - { from: commit, to: END }
			outcomes:
			  review: [{ status: CLOSED }]
			  validate: [{ status: AWAITING_DECISION, next: job-decision }]
			  commit: [{ status: DONE }]
			""";

	private RepairsFlow() {
	}

	/** A copy of the repairs pack in {@code target} with these two graphs instead of graph.yaml. */
	public static Path pack(Path target) {
		return pack(target, INTAKE, DECISION);
	}

	public static Path pack(Path target, String intake, String decision) {
		Path dir = RepairsPack.copyWith(target, "graph.yaml", s -> s);
		try {
			Files.delete(dir.resolve("graph.yaml"));
			Files.createDirectories(dir.resolve("graphs"));
			Files.writeString(dir.resolve("graphs/job-intake.yaml"), intake);
			Files.writeString(dir.resolve("graphs/job-decision.yaml"), decision);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return dir;
	}

	public static List<PackGraph> load(Path dir) {
		return new PackGraphLoader().loadAll("repairs", dir);
	}
}

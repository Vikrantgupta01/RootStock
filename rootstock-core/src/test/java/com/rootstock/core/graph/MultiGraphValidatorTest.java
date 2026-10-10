package com.rootstock.core.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pack with several graphs: each checked, and what spans them. */
class MultiGraphValidatorTest {

	@TempDir
	Path tmp;

	private List<String> problems(UnaryOperator<String> intake, UnaryOperator<String> decision) {
		Path dir = RepairsFlow.pack(tmp.resolve("p" + System.nanoTime()), intake.apply(RepairsFlow.INTAKE),
				decision.apply(RepairsFlow.DECISION));
		return new GraphValidator().validatePack(RepairsFlow.load(dir), RepairsPack.context()).stream()
				.map(GraphProblem::toString).toList();
	}

	@Test
	void theTwoGraphPackIsValid() {
		assertThat(problems(s -> s, s -> s)).isEmpty();
	}

	@Test
	void inAGraphOnlyAnApproverStartsItsReviewNodeIsTheGate() {
		// The same writes, but anyone may start the graph: review no longer gates commit.
		assertThat(problems(s -> s, s -> s.replace("trigger: { kind: decision, approverRoles: [property-manager] }",
				"trigger: { kind: submit }"))).contains("graphs/job-decision.yaml node commit: "
						+ GraphValidatorTest.UNGATED + " (there is no such node)");
	}

	@Test
	void aWriteThatSkipsReviewIsRejectedEvenInADecisionGraph() {
		assertThat(problems(s -> s, s -> s.replace("{ from: START, to: review }", "{ from: START, to: commit }")))
				.anyMatch(p -> p.startsWith("graphs/job-decision.yaml node commit: " + GraphValidatorTest.UNGATED));
	}

	@Test
	void everyNodeARunCanEndAfterHasAnOutcome() {
		assertThat(problems(s -> s.replace("  chase: [{ status: AWAITING_DECISION, next: job-decision }]\n", ""), s -> s))
				.containsExactly("graphs/job-intake.yaml outcomes: a run can end after 'chase'; say how the case then stands");
	}

	@Test
	void anOutcomeGoesOnOnlyToAGraphADecisionStarts() {
		assertThat(problems(s -> s.replace("draft: [{ status: AWAITING_DECISION, next: job-decision }]",
				"draft: [{ status: AWAITING_DECISION, next: job-intake }]"), s -> s))
				.containsExactly("graphs/job-intake.yaml outcomes.draft[0].next: graph 'job-intake' starts a new case "
						+ "(trigger submit); a case can only go on to a graph started by a decision");
		assertThat(problems(s -> s.replace("next: job-decision }]\n  chase", "next: job-review }]\n  chase"), s -> s))
				.anyMatch(p -> p.contains("unknown graph 'job-review'"));
	}

	@Test
	void aPackStartsCasesWithExactlyOneGraph() {
		assertThat(problems(s -> s, s -> s.replace("trigger: { kind: decision, approverRoles: [property-manager] }",
				"trigger: { kind: submit }")))
				.anyMatch(p -> p.endsWith("trigger: a pack needs exactly one graph a case starts with (trigger kind "
						+ "submit); it has 2: [job-decision, job-intake]"));
	}

	@Test
	void aDecisionSaysWhoMayMakeIt() {
		assertThat(problems(s -> s, s -> s.replace("trigger: { kind: decision, approverRoles: [property-manager] }",
				"trigger: { kind: decision }")))
				.contains("graphs/job-decision.yaml trigger.approverRoles: a graph started by a decision says who may make it");
	}

	@Test
	void anAgentNoGraphUsesIsReportedOnceForThePack() {
		// tenant-chaser is used by job-intake only; dropping it from there leaves it unused by both.
		assertThat(problems(s -> s.replace("  - { id: chase, agent: tenant-chaser, config: { actionType: CHASE_MESSAGE } }\n",
				"").replace("      - { when: { issues.anySeverity: BLOCKING }, to: chase }\n", "")
				.replace("  - { from: chase, to: END }\n", "")
				.replace("  chase: [{ status: AWAITING_DECISION, next: job-decision }]\n", ""), s -> s))
				.containsExactly("agents/tenant-chaser.yaml: agent 'tenant-chaser' is not used by any node");
	}
}

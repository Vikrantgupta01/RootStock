package com.rootstock.core.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.UserRole;
import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.GraphCompiler;
import com.rootstock.core.graph.GraphValidator;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.graph.NodeRegistry;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.RepairsFlow;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.core.graph.stub.StubNodes;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.action.NodeAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A case through two graphs, one short run each: intake ends with the case
 * waiting for a decision (as data, no run held open); the decision starts the
 * decision graph, which re-checks the case before it writes. The stub nodes.
 */
class CaseFlowTest {

	@TempDir
	Path tmp;

	private CaseRunService service;
	private CaseDecisions decisions;

	private static final AuthContext.Principal MEMBER = new AuthContext.Principal("member-1", "m@example.com",
			UserRole.VIEWER, Set.of());
	private static final AuthContext.Principal MANAGER = new AuthContext.Principal("manager-1", "pm@example.com",
			UserRole.VIEWER, Set.of("property-manager"));

	private void start(NodeRegistry registry) {
		List<PackGraph> pack = RepairsFlow.load(RepairsFlow.pack(tmp.resolve("repairs")));
		assertThat(new GraphValidator().validatePack(pack, new GraphValidator.Context(registry, Set.of(),
				RepairsPack.tools(), RepairsPack.ontology(), null))).isEmpty();
		RunRegistry runs = new RunRegistry(List.of());
		GraphCompiler compiler = new GraphCompiler(registry, List.of(), runs);
		List<CaseGraph> graphs = pack.stream().map(g -> compiler.compile(g, RepairsPack.ontology())).toList();
		CaseGraphs all = new CaseGraphs(graphs);
		service = new CaseRunService(all, runs, CaseStore.inMemory());
		decisions = new CaseDecisions(service, runs, all, Clock.systemUTC());
	}

	@AfterEach
	void close() {
		if (service != null) {
			service.close();
		}
	}

	private CaseFile settled(String caseId) throws InterruptedException {
		for (int i = 0; i < 200; i++) {
			CaseFile f = service.caseFile(caseId).orElseThrow();
			if (!CaseFile.RUNNING.equals(f.status())) {
				return f;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("still running");
	}

	@SuppressWarnings("unchecked")
	private static List<String> auditNodes(CaseFile f) {
		return ((List<AuditEntry>) f.state().get(AuditEntry.CHANNEL)).stream().map(AuditEntry::node).toList();
	}

	@Test
	void intakeEndsWaitingForADecisionWhichRunsTheDecisionGraphToTheEnd() throws Exception {
		start(RepairsPack.registry());
		CaseRun intake = service.start(null, "Tap leaking", Map.of(), "member-1");
		CaseFile waiting = settled(intake.caseId());

		assertThat(intake.status()).isEqualTo(CaseRun.Status.COMPLETED);
		assertThat(waiting.status()).isEqualTo("AWAITING_DECISION");
		assertThat(waiting.next()).isEqualTo("job-decision");
		assertThat(waiting.waitingFor()).containsExactly("property-manager");
		assertThat(waiting.state()).containsKeys("record", "actions").doesNotContainKey(CaseState.RUN_ID);
		assertThat(decisions.awaiting(MANAGER)).extracting(CaseRun::caseId).containsExactly(intake.caseId());
		assertThat(decisions.awaiting(MEMBER)).isEmpty();

		CaseRun decision = decisions.decide(intake.caseId(), CaseDecisions.Decision.APPROVED, null, "Book it", MANAGER);
		CaseFile done = settled(intake.caseId());

		assertThat(decision.graph()).isEqualTo("job-decision");
		assertThat(decision.runId()).isNotEqualTo(intake.runId());
		assertThat(done.status()).isEqualTo("DONE");
		assertThat(done.next()).isNull();
		// One case, two runs: the audit runs on across both.
		assertThat(auditNodes(done)).containsSubsequence("ingest", "extract", "draft", "review", "enrich", "validate",
				"commit");
		assertThat(service.caseFile(intake.caseId()).orElseThrow().startedBy()).isEqualTo("member-1");
	}

	@Test
	void aRejectionClosesTheCaseAfterRecordingIt() throws Exception {
		start(RepairsPack.registry());
		CaseRun intake = service.start(null, "Tap leaking", Map.of(), "member-1");
		settled(intake.caseId());

		decisions.decide(intake.caseId(), CaseDecisions.Decision.REJECTED, null, "Not ours to fix", MANAGER);
		CaseFile closed = settled(intake.caseId());

		assertThat(closed.status()).isEqualTo("CLOSED");
		assertThat(auditNodes(closed)).endsWith("review").doesNotContain("commit");
	}

	@Test
	void aBlockingIssueOnTheFreshCheckSendsTheCaseBackNotToCommit() throws Exception {
		start(RepairsPack.registry());
		// chase: a blocking issue only the tenant can answer, raised again on every check.
		CaseRun intake = service.start(null, "Tap leaking", Map.of(StubNodes.SIMULATE, "chase"), "member-1");
		assertThat(settled(intake.caseId()).status()).isEqualTo("AWAITING_DECISION");

		decisions.decide(intake.caseId(), CaseDecisions.Decision.APPROVED, null, null, MANAGER);
		CaseFile back = settled(intake.caseId());

		assertThat(back.status()).isEqualTo("AWAITING_DECISION");
		assertThat(back.next()).isEqualTo("job-decision");
		assertThat(auditNodes(back)).doesNotContain("commit");
	}

	@Test
	void aWarningTheReviewerWasNotShownSendsTheCaseBack() throws Exception {
		// A rules node that finds nothing at intake, and a new warning once the case was decided.
		NodeFactory rules = new NodeFactory() {

			@Override
			public Kind kind() {
				return Kind.NODE;
			}

			@Override
			public String type() {
				return "rules";
			}

			@Override
			public NodeAction<CaseState> create(NodeContext context) {
				return state -> Map.of("issues", state.value(CaseState.REVIEW).isEmpty() ? List.of()
						: List.of(new CaseIssue("R03", CaseIssue.WARNING, CaseIssue.REVIEWER,
								"A repeat request since you looked", "defects[0].trade", CaseIssue.BUSINESS)));
			}
		};
		List<NodeFactory> factories = new ArrayList<>(StubNodes.all(Duration.ZERO).stream()
				.filter(f -> !f.type().equals("rules")).toList());
		factories.add(rules);
		start(new NodeRegistry(factories));
		CaseRun intake = service.start(null, "Tap leaking", Map.of(), "member-1");
		settled(intake.caseId());

		decisions.decide(intake.caseId(), CaseDecisions.Decision.APPROVED, null, null, MANAGER);

		CaseFile back = settled(intake.caseId());
		assertThat(back.status()).isEqualTo("AWAITING_DECISION");
		assertThat(auditNodes(back)).doesNotContain("commit");
	}

	@Test
	void onlyAnApproverMayDecideAndOnlyOnce() throws Exception {
		start(RepairsPack.registry());
		CaseRun intake = service.start(null, "Tap leaking", Map.of(), "member-1");
		settled(intake.caseId());

		assertThatThrownBy(() -> decisions.decide(intake.caseId(), CaseDecisions.Decision.APPROVED, null, null, MEMBER))
				.isInstanceOfSatisfying(CaseDecisions.DecisionException.class,
						e -> assertThat(e.reason()).isEqualTo(CaseDecisions.DecisionException.Reason.FORBIDDEN));
		decisions.decide(intake.caseId(), CaseDecisions.Decision.APPROVED, null, null, MANAGER);
		assertThatThrownBy(() -> decisions.decide(intake.caseId(), CaseDecisions.Decision.APPROVED, null, null,
				MANAGER)).isInstanceOfSatisfying(CaseDecisions.DecisionException.class,
						e -> assertThat(e.reason()).isEqualTo(CaseDecisions.DecisionException.Reason.CONFLICT));
	}
}

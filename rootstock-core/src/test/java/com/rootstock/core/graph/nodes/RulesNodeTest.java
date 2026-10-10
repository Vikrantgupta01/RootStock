package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.Lookup;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.core.graph.stub.StubNodes;
import com.rootstock.core.rules.RuleKinds;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.bsc.langgraph4j.action.NodeAction;
import org.junit.jupiter.api.Test;

/** The four validation layers, on the repairs pack (its ontology, rules.yaml and judge). */
class RulesNodeTest {

	private final PackGraph pack = RepairsPack.load();
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T01:00:00Z"), ZoneOffset.UTC);

	private NodeAction<CaseState> rules(NodeFactory judge) {
		RulesNode node = new RulesNode(RuleKinds.of(List.of()),
				type -> judge != null && type.equals(JudgeAgent.TYPE) ? Optional.of(judge) : Optional.empty(), clock);
		return node.create(new NodeContext("repairs", pack.graph().node("validate"), null, RepairsPack.ontology(), pack));
	}

	private NodeAction<CaseState> rules() {
		return rules(null);
	}

	/** Nothing missing, no rule broken: priority LOW, so no phone is needed. */
	private static Map<String, Object> complete() {
		Map<String, Object> record = new HashMap<>();
		record.put("priority", "LOW");
		record.put("reportedOn", "2026-10-01");
		record.put("tenant", Map.of("name", "Sam"));
		record.put("defects", List.of(Map.of("trade", "PLUMBING")));
		return record;
	}

	private static CaseState state(Map<String, Object> record, Map<String, Object> more) {
		Map<String, Object> data = new LinkedHashMap<>(Map.of(CaseState.CASE_ID, "c", "record", record,
				CaseState.OPTIONS, Map.of()));
		data.putAll(more);
		return new CaseState(data);
	}

	private static List<CaseIssue> issues(Map<String, Object> update) {
		return update.get("issues") instanceof List<?> l ? l.stream().map(CaseIssue.class::cast).toList() : List.of();
	}

	@Test
	void aCompleteRecordPassesEveryLayer() throws Exception {
		Map<String, Object> update = rules().apply(state(complete(), Map.of()));

		assertThat(issues(update)).isEmpty();
		assertThat(update.get(AuditEntry.CHANNEL)).isEqualTo(List.of(new AuditEntry("validate",
				"structural ok; semantic ok; business ok; judgment ok")));
	}

	@Test
	void structuralAMissingRequiredFieldIsForTheSubmitter() throws Exception {
		Map<String, Object> record = complete();
		record.put("reportedOn", null);
		record.put("defects", List.of());

		assertThat(issues(rules().apply(state(record, Map.of())))).containsExactly(
				new CaseIssue(RulesNode.REQUIRED_FIELD, CaseIssue.BLOCKING, CaseIssue.SUBMITTER, "Missing reportedOn",
						"reportedOn", CaseIssue.STRUCTURAL),
				new CaseIssue(RulesNode.REQUIRED_FIELD, CaseIssue.BLOCKING, CaseIssue.SUBMITTER,
						"Missing defects (One thing that needs fixing.)", "defects", CaseIssue.STRUCTURAL));
	}

	@Test
	void structuralAValueAReviewerMistypedIsForTheReviewer() throws Exception {
		Map<String, Object> record = complete();
		record.put("priority", "SOMEDAY");

		assertThat(issues(rules().apply(state(record, Map.of())))).singleElement().satisfies(i -> {
			assertThat(i.ruleId()).isEqualTo(RulesNode.SCHEMA);
			assertThat(i.path()).isEqualTo("priority");
			assertThat(i.answerableBy()).isEqualTo(CaseIssue.REVIEWER);
			assertThat(i.layer()).isEqualTo(CaseIssue.STRUCTURAL);
		});
	}

	@Test
	void semanticAnOntologyConstraintIsChecked() throws Exception {
		// hazards-are-urgent: a job with any hazard is urgent.
		Map<String, Object> record = complete();
		record.put("hazards", List.of("FLOODING"));

		assertThat(issues(rules().apply(state(record, Map.of())))).containsExactly(new CaseIssue("hazards-are-urgent",
				CaseIssue.BLOCKING, CaseIssue.SUBMITTER, "A job with any hazard is urgent.", "priority",
				CaseIssue.SEMANTIC));
	}

	@Test
	void businessThePacksRulesRunWithThresholdsFromLookups() throws Exception {
		Map<String, Object> record = complete();
		record.put("visits", List.of(Map.of("trade", "PLUMBING", "estimateAud", 900)));
		Map<String, Object> context = Map.of("lookups", List.of(new Lookup("plan", "list_contractors",
				Map.of("trade", "PLUMBING"), "OK", Map.of("maxEstimateAud", 500), null)));

		assertThat(issues(rules().apply(state(record, Map.of("context", context))))).containsExactly(new CaseIssue(
				"estimate-limit", CaseIssue.WARNING, CaseIssue.REVIEWER, "PLUMBING estimate of $900 is over the $500 cap",
				"visits[0].estimateAud", CaseIssue.BUSINESS));
	}

	@Test
	void judgmentTheJudgesFindingsAreAddedAndItsAuditKept() throws Exception {
		NodeFactory judge = fakeJudge(state -> Map.of("issues", List.of(new CaseIssue("job-judge", CaseIssue.WARNING,
				CaseIssue.REVIEWER, "The report never mentions a kitchen", "defects[0].room", CaseIssue.JUDGMENT)),
				AuditEntry.CHANNEL, List.of(new AuditEntry("validate", "Judge job-judge: Flagged 1 thing(s)"))));

		Map<String, Object> update = rules(judge).apply(state(complete(), Map.of()));

		assertThat(issues(update)).extracting(CaseIssue::layer).containsExactly(CaseIssue.JUDGMENT);
		assertThat(update.get(AuditEntry.CHANNEL)).asInstanceOf(InstanceOfAssertFactories.LIST).containsExactly(
				new AuditEntry("validate", "Judge job-judge: Flagged 1 thing(s)"),
				new AuditEntry("validate", "structural ok; semantic ok; business ok; judgment 1 [job-judge]"));
	}

	@Test
	void aJudgeThatFailsIsAWarningNotAFailedCase() throws Exception {
		NodeFactory judge = fakeJudge(state -> {
			throw new IllegalStateException("model unavailable");
		});

		assertThat(issues(rules(judge).apply(state(complete(), Map.of())))).singleElement().satisfies(i -> {
			assertThat(i.severity()).isEqualTo(CaseIssue.WARNING);
			assertThat(i.layer()).isEqualTo(CaseIssue.JUDGMENT);
			assertThat(i.message()).contains("could not run (model unavailable)");
		});
	}

	@Test
	void theSimulateOptionStillRaisesTheStubsIssue() throws Exception {
		Map<String, Object> update = rules().apply(state(complete(), Map.of(CaseState.OPTIONS,
				Map.of(StubNodes.SIMULATE, "chase"))));

		assertThat(issues(update)).singleElement().extracting(CaseIssue::answerableBy).isEqualTo(CaseIssue.EXTERNAL);
	}

	@Test
	void beforeAnyRecordThereIsNothingToCheck() throws Exception {
		Map<String, Object> update = rules().apply(new CaseState(Map.of(CaseState.CASE_ID, "c")));

		assertThat(update).containsEntry("issues", List.of());
	}

	private static NodeFactory fakeJudge(NodeAction<CaseState> action) {
		return new NodeFactory() {

			@Override
			public Kind kind() {
				return Kind.AGENT;
			}

			@Override
			public String type() {
				return JudgeAgent.TYPE;
			}

			@Override
			public NodeAction<CaseState> create(NodeContext context) {
				return action;
			}
		};
	}
}

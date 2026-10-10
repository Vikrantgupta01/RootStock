package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.core.graph.stub.StubNodes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.action.NodeAction;
import org.junit.jupiter.api.Test;

class RulesNodeTest {

	private final PackGraph pack = RepairsPack.load();
	private final NodeAction<CaseState> rules = new RulesNode().create(
			new NodeContext("repairs", pack.graph().node("validate"), null, RepairsPack.ontology(), pack));

	@Test
	void eachMissingRequiredFieldIsABlockingIssueTheSubmitterCanAnswer() throws Exception {
		Map<String, Object> record = new HashMap<>();
		record.put("priority", "URGENT");
		record.put("reportedOn", null);
		record.put("tenant", Map.of("name", "Sam"));
		record.put("defects", List.of());

		Map<String, Object> update = rules.apply(state(record, Map.of()));

		assertThat(update.get("issues")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
				.containsExactly(
						new CaseIssue(RulesNode.REQUIRED_FIELD, CaseIssue.BLOCKING, CaseIssue.SUBMITTER,
								"Missing reportedOn", "reportedOn"),
						new CaseIssue(RulesNode.REQUIRED_FIELD, CaseIssue.BLOCKING, CaseIssue.SUBMITTER,
								"Missing defects (One thing that needs fixing.)", "defects"));
		assertThat(update.get(AuditEntry.CHANNEL)).isEqualTo(List.of(new AuditEntry("validate",
				"record: 2 required field(s) missing [reportedOn, defects]")));
	}

	@Test
	void aCompleteRecordHasNoIssues() throws Exception {
		Map<String, Object> record = Map.of("priority", "LOW", "reportedOn", "2026-10-01", "tenant", Map.of("name", "Sam"),
				"defects", List.of(Map.of("trade", "PLUMBING")));

		assertThat(rules.apply(state(record, Map.of()))).containsEntry("issues", List.of());
	}

	@Test
	void theSimulateOptionStillRaisesTheStubsIssue() throws Exception {
		Map<String, Object> record = Map.of("priority", "LOW", "reportedOn", "2026-10-01", "tenant", Map.of("name", "Sam"),
				"defects", List.of(Map.of("trade", "PLUMBING")));

		Map<String, Object> update = rules.apply(state(record, Map.of(StubNodes.SIMULATE, "chase")));

		assertThat(update.get("issues")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
				.singleElement().extracting("answerableBy").isEqualTo(CaseIssue.EXTERNAL);
	}

	@Test
	void beforeAnyRecordThereIsNothingToCheck() throws Exception {
		Map<String, Object> update = rules.apply(new CaseState(Map.of(CaseState.CASE_ID, "c")));

		assertThat(update).containsEntry("issues", List.of());
	}

	private static CaseState state(Map<String, Object> record, Map<String, Object> options) {
		return new CaseState(Map.of(CaseState.CASE_ID, "c", "record", record, CaseState.OPTIONS, options));
	}
}

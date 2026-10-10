package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.RepairsPack;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.action.NodeAction;
import org.junit.jupiter.api.Test;

class HumanReviewNodeTest {

	private final PackGraph pack = RepairsPack.load();
	private final NodeAction<CaseState> review = new HumanReviewNode()
			.create(new NodeContext("repairs", pack.graph().node("review"), null, null, pack));

	@Test
	void recordsWhoDecidedWhatInTheAudit() throws Exception {
		Map<String, Object> update = review.apply(new CaseState(Map.of(CaseState.CASE_ID, "c", CaseState.REVIEW,
				Map.of("decision", "APPROVED", "by", "u-1", "byEmail", "kim@example.com", "comment", "Go ahead"))));

		assertThat(update.get(AuditEntry.CHANNEL)).isEqualTo(List.of(new AuditEntry("review",
				"APPROVED by kim@example.com: Go ahead")));
	}

	@Test
	void reachedWithoutADecisionTheRunFails() {
		assertThatThrownBy(() -> review.apply(new CaseState(Map.of(CaseState.CASE_ID, "c"))))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("without a review decision");
	}
}

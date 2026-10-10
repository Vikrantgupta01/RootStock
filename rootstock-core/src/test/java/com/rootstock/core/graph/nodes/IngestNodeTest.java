package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.RepairsPack;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestNodeTest {

	@Test
	void tidiesTheTextWithoutChangingWhatItSays() {
		assertThat(IngestNode.normalise("  Tap leaking \r\n\r\n\r\n\r\nsince Mon​day\t \rcafé \u0007 "))
				.isEqualTo("Tap leaking\n\nsince Monday\ncafé");
	}

	@Test
	void recordsTheCaseAndTraceIdsAndTheTidiedText() throws Exception {
		PackGraph pack = RepairsPack.load();
		NodeContext context = new NodeContext("repairs", pack.graph().node("ingest"), null, null, pack);

		Map<String, Object> update = new IngestNode().create(context).apply(new CaseState(Map.of(
				CaseState.CASE_ID, "case-1", CaseState.TRACE_ID, "abc123", CaseState.RAW_INPUT, " Tap leaking ")));

		assertThat(update).containsEntry(CaseState.RAW_INPUT, "Tap leaking");
		assertThat(update.get(AuditEntry.CHANNEL)).isEqualTo(List.of(new AuditEntry("ingest",
				"Case case-1, trace abc123: 2 words (tidied: 13 → 11 characters)")));
	}
}

package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.core.llm.BundledPrompts;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.ScriptedChatModel;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JudgeAgentTest {

	private final PackGraph pack = RepairsPack.load();

	private Map<String, Object> run(ScriptedChatModel model) throws Exception {
		PromptRegistry prompts = new PromptRegistry(null, Duration.ofMinutes(5), Clock.systemUTC());
		prompts.addBundled(BundledPrompts.load(RepairsPack.dir()));
		NodeContext context = new NodeContext("repairs", pack.graph().node("validate"), pack.agents().get("job-judge"),
				RepairsPack.ontology(), pack);
		return new JudgeAgent(model.service(), prompts, Clock.systemUTC()).create(context).apply(new CaseState(Map.of(
				CaseState.CASE_ID, "c", CaseState.RAW_INPUT, "Tap leaking in the bathroom",
				"record", Map.of("defects", List.of(Map.of("trade", "PLUMBING", "room", "kitchen"))))));
	}

	@SuppressWarnings("unchecked")
	private static List<CaseIssue> issues(Map<String, Object> update) {
		return (List<CaseIssue>) update.get("issues");
	}

	@Test
	void eachFindingIsAWarningForTheReviewerWithItsEvidence() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel("""
				{"issues": [{"path": "defects[0].room", "problem": "The room is the kitchen, but the report says bathroom.",
				             "evidence": "in the bathroom"}]}""");

		Map<String, Object> update = run(model);

		assertThat(issues(update)).containsExactly(new CaseIssue("job-judge", CaseIssue.WARNING, CaseIssue.REVIEWER,
				"The room is the kitchen, but the report says bathroom. (input: \"in the bathroom\")", "defects[0].room",
				CaseIssue.JUDGMENT));
		assertThat(update.get(AuditEntry.CHANNEL).toString()).contains("Flagged 1 thing(s) with prompt repairs/judge");
		String system = model.prompts().getFirst().getInstructions().getFirst().getText();
		assertThat(system).contains("\"evidence\"").doesNotContain("{{");
		assertThat(model.prompts().getFirst().getInstructions().get(1).getText()).contains("bathroom").contains("kitchen");
	}

	@Test
	void aFaithfulRecordHasNoFindings() throws Exception {
		assertThat(issues(run(new ScriptedChatModel("{\"issues\": []}")))).isEmpty();
	}

	@Test
	void anUnusableAnswerIsRetriedThenBecomesAWarning() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel("Looks fine to me!");

		Map<String, Object> update = run(model);

		// The repairs judge sets no limits: one retry, so two attempts.
		assertThat(model.prompts()).hasSize(2);
		assertThat(issues(update)).singleElement().satisfies(i -> {
			assertThat(i.severity()).isEqualTo(CaseIssue.WARNING);
			assertThat(i.message()).contains("no usable answer after 2 attempt(s)");
		});
	}
}

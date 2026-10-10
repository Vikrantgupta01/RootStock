package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseParkedException;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.ProposedAction;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.core.llm.BundledPrompts;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.ScriptedChatModel;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The repairs draft node: the visit-planner agent may only propose VISIT actions (actionType: VISIT). */
class DrafterAgentTest {

	private final PackGraph pack = RepairsPack.load();

	private Map<String, Object> run(ScriptedChatModel model) throws Exception {
		PromptRegistry prompts = new PromptRegistry(null, Duration.ofMinutes(5), Clock.systemUTC());
		prompts.addBundled(BundledPrompts.load(RepairsPack.dir()));
		NodeContext context = new NodeContext("repairs", pack.graph().node("draft"), pack.agents().get("visit-planner"),
				RepairsPack.ontology(), pack);
		return new DrafterAgent(model.service(), prompts, Clock.systemUTC()).create(context).apply(new CaseState(Map.of(
				CaseState.CASE_ID, "c", "record", Map.of("priority", "URGENT", "defects", List.of(Map.of("trade",
						"PLUMBING"))))));
	}

	@Test
	void theDraftIsTheProposedActionsWithTheirDetails() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel("""
				{"actions": [{"type": "VISIT", "summary": "Plumber tomorrow morning",
				              "details": {"trade": "PLUMBING", "when": "2026-10-12", "note": null}}]}""");

		Map<String, Object> update = run(model);

		assertThat(update.get("actions")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
				.singleElement().isInstanceOfSatisfying(ProposedAction.class, a -> {
					assertThat(a.type()).isEqualTo("VISIT");
					assertThat(a.summary()).isEqualTo("Plumber tomorrow morning");
					assertThat(a.details()).containsEntry("trade", "PLUMBING").containsEntry("note", null);
				});
		assertThat(update.get(AuditEntry.CHANNEL).toString()).contains("Drafted 1 action(s) [VISIT]");
		String system = model.prompts().getFirst().getInstructions().getFirst().getText();
		assertThat(system).contains("Allowed: VISIT").contains("\"enum\": [\"VISIT\"]").doesNotContain("{{");
	}

	@Test
	void anActionTypeTheNodeDoesNotAllowIsRetriedThenTheCaseIsParked() {
		ScriptedChatModel model = new ScriptedChatModel("""
				{"actions": [{"type": "DEMOLISH", "summary": "Knock it down", "details": {}}]}""");

		assertThatThrownBy(() -> run(model)).isInstanceOf(CaseParkedException.class)
				.hasMessageContaining("did not match after 2 attempt(s)");
		assertThat(model.prompts()).hasSize(2);
	}

	@Test
	void theDraftsSchemaListsOnlyTheAllowedTypes() {
		assertThat(DrafterAgent.actionTypes(Map.of("actionTypes", List.of("REFERRAL", "FOLLOW_UP"))))
				.containsExactly("REFERRAL", "FOLLOW_UP");
		assertThat(DrafterAgent.actionTypes(Map.of("actionType", "CHASE_MESSAGE"))).containsExactly("CHASE_MESSAGE");
		assertThat(DrafterAgent.schema(List.of("A", "B"))).contains("\"enum\": [\"A\",\"B\"]");
	}
}

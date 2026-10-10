package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseParkedException;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.core.llm.BundledPrompts;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.ScriptedChatModel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

public class StructuredExtractionTest {

	public static final String VALID = """
			Here you go:
			```json
			{"priority": "URGENT", "hazards": ["FLOODING"], "reportedOn": null,
			 "tenant": {"name": "Sam", "phone": null},
			 "defects": [{"trade": "PLUMBING", "room": "kitchen"}], "visits": []}
			```""";

	public static final String WRONG_CODE = """
			{"priority": "VERY_URGENT", "reportedOn": "2026-10-01", "tenant": null, "defects": []}""";

	static final String BAD_DATE = """
			{"priority": "LOW", "reportedOn": "last Monday", "tenant": null, "defects": []}""";

	private final PackGraph pack = RepairsPack.load();
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T01:00:00Z"), ZoneOffset.UTC);

	private Map<String, Object> run(ScriptedChatModel model) throws Exception {
		PromptRegistry prompts = new PromptRegistry(null, Duration.ofMinutes(5), clock);
		prompts.addBundled(BundledPrompts.load(RepairsPack.dir()));
		NodeContext context = new NodeContext("repairs", pack.graph().node("extract"), pack.agents().get("job-extractor"),
				RepairsPack.ontology(), pack);
		return new StructuredExtraction(model.service(), prompts, clock).create(context)
				.apply(new CaseState(Map.of(CaseState.CASE_ID, "c", CaseState.RAW_INPUT, "Water everywhere in the kitchen")));
	}

	@Test
	void aMatchingReplyBecomesTheRecordWithNullsKept() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel(VALID);

		Map<String, Object> update = run(model);

		@SuppressWarnings("unchecked")
		Map<String, Object> record = (Map<String, Object>) update.get("record");
		assertThat(record).containsEntry("priority", "URGENT").containsEntry("reportedOn", null)
				.containsEntry("hazards", List.of("FLOODING"));
		assertThat(update.get(AuditEntry.CHANNEL)).isEqualTo(List.of(new AuditEntry("extract",
				"Extracted 4 of 6 top-level fields of 'job-intake' with prompt repairs/extract-job (bundled), "
						+ "model profile 'extraction'")));
		assertThat(model.prompts()).hasSize(1);
	}

	@Test
	void thePromptGetsTheInputSchemaGlossaryAndDate() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel(VALID);

		run(model);

		List<Message> sent = model.prompts().getFirst().getInstructions();
		assertThat(sent.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
		assertThat(sent.get(0).getText()).contains("Today is 2026-10-10")
				.contains("\"reportedOn\"").contains("\"null\"")
				.contains("water everywhere")
				.doesNotContain("propertyRef").doesNotContain("{{");
		assertThat(sent.get(1).getText()).isEqualTo("Report: Water everywhere in the kitchen");
	}

	@Test
	void aReplyThatDoesNotMatchIsRetriedWithWhatWasWrong() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel(WRONG_CODE, VALID);

		Map<String, Object> update = run(model);

		assertThat(update).containsKey("record");
		assertThat(update.get(AuditEntry.CHANNEL).toString()).contains("on attempt 2");
		List<Message> retry = model.prompts().get(1).getInstructions();
		assertThat(retry).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM, MessageType.USER,
				MessageType.ASSISTANT, MessageType.USER);
		assertThat(retry.getLast().getText()).contains("does not match the schema").contains("priority");
	}

	@Test
	void aDateThatIsNotADateDoesNotMatch() {
		StructuredExtraction.Attempt attempt = check(BAD_DATE);

		assertThat(attempt.problems()).singleElement().asString().contains("reportedOn");
	}

	@Test
	void textThatIsNotJsonDoesNotMatch() {
		assertThat(check("Sorry, I can't help with that.").problems()).containsExactly("the reply holds no JSON object");
		assertThat(check("{\"priority\": }").problems()).singleElement().asString().startsWith("the reply is not valid JSON");
	}

	@Test
	void aReplyThatNeverMatchesParksTheCase() {
		// The repairs extractor sets no limits: two retries, so three attempts in all.
		ScriptedChatModel model = new ScriptedChatModel(WRONG_CODE);

		assertThatThrownBy(() -> run(model)).isInstanceOf(CaseParkedException.class)
				.hasMessageContaining("did not match 'job-intake' after 3 attempt(s)")
				.hasMessageContaining("priority");
		assertThat(model.prompts()).hasSize(3);
	}

	private StructuredExtraction.Attempt check(String reply) {
		var schema = com.networknt.schema.SchemaRegistry
				.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
				.getSchema(new com.rootstock.core.ontology.JsonSchemaGenerator().generateJson(RepairsPack.ontology(),
						"job-intake", com.rootstock.core.ontology.JsonSchemaGenerator.Mode.EXTRACTION));
		return StructuredExtraction.check(reply, schema);
	}
}

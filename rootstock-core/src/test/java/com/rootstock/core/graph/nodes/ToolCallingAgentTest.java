package com.rootstock.core.graph.nodes;

import static com.rootstock.core.llm.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.Lookup;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.core.llm.BundledPrompts;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.ScriptedChatModel;
import com.rootstock.core.tools.ToolAccess;
import com.rootstock.core.tools.ToolCallContext;
import com.rootstock.core.tools.ToolCatalog;
import com.rootstock.core.tools.ToolDefinition;
import com.rootstock.core.tools.ToolGateway;
import com.rootstock.core.tools.ToolInvoker;
import com.rootstock.core.tools.ToolSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * The bounded lookup loop against a scripted model and a fake client system,
 * through a real ToolGateway with the repairs pack's catalog.
 */
class ToolCallingAgentTest {

	private final PackGraph pack = RepairsPack.load();
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T01:00:00Z"), ZoneOffset.UTC);
	private final List<String> sent = new CopyOnWriteArrayList<>();

	private final ToolInvoker landlord = new ToolInvoker() {

		@Override
		public String invoke(ToolDefinition tool, Map<String, Object> arguments, ToolCallContext context) {
			sent.add(tool.name() + " " + new java.util.TreeMap<>(arguments));
			return switch (tool.name()) {
				case "find_property" -> "{\"propertyRef\": \"P-7\", \"address\": \"1 Main St\"}";
				case "list_contractors" -> "[{\"name\": \"Drip Fixers\", \"trade\": \"" + arguments.get("trade") + "\"}]";
				default -> throw new ToolErrorException("no such thing");
			};
		}

		@Override
		public Optional<ToolSpec> describe(ToolDefinition tool) {
			return Optional.of(new ToolSpec(tool.name(), "Does " + tool.name(), "{\"type\":\"object\"}"));
		}
	};

	private Map<String, Object> run(ScriptedChatModel model, ToolCatalog catalog, Map<String, Object> record)
			throws Exception {
		PromptRegistry prompts = new PromptRegistry(null, Duration.ofMinutes(5), clock);
		prompts.addBundled(BundledPrompts.load(RepairsPack.dir()));
		NodeContext context = new NodeContext("repairs", pack.graph().node("enrich"), pack.agents().get("property-lookup"),
				RepairsPack.ontology(), pack);
		ToolCallingAgent agent = new ToolCallingAgent(model.service(), prompts, new ToolGateway(catalog, landlord), clock);
		return agent.create(context).apply(new CaseState(Map.of(CaseState.CASE_ID, "c", "record", record)));
	}

	private Map<String, Object> run(ScriptedChatModel model) throws Exception {
		return run(model, RepairsPack.tools(), Map.of("defects", List.of(Map.of("trade", "PLUMBING"))));
	}

	@SuppressWarnings("unchecked")
	private static List<Lookup> lookups(Map<String, Object> update) {
		return (List<Lookup>) ((Map<String, Object>) update.get("context")).get("lookups");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> context(Map<String, Object> update) {
		return (Map<String, Object>) update.get("context");
	}

	@Test
	void thePlanRunsFirstThenTheModelLooksUpWhatItNeedsThenSummarises() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel(toolCall("c1", "find_property", "{\"address\":\"1 Main St\"}"),
				new AssistantMessage("Property P-7; Drip Fixers can do the plumbing."));

		Map<String, Object> update = run(model);

		assertThat(sent).containsExactly("list_contractors {trade=PLUMBING}", "find_property {address=1 Main St}");
		assertThat(lookups(update)).extracting(Lookup::source, Lookup::tool,
				Lookup::status).containsExactly(
						org.assertj.core.groups.Tuple.tuple("plan", "list_contractors", "OK"),
						org.assertj.core.groups.Tuple.tuple("model", "find_property", "OK"));
		assertThat(lookups(update).get(1).result()).isEqualTo(Map.of("propertyRef", "P-7", "address", "1 Main St"));
		assertThat(context(update)).containsEntry("summary", "Property P-7; Drip Fixers can do the plumbing.")
				.containsEntry("modelCalls", 2);
		assertThat(update.get(AuditEntry.CHANNEL).toString()).contains("1 planned, 1 asked for by the model");

		// The model saw the plan's results, was offered only the agent's tools, and got the tool's answer back.
		List<Message> first = model.prompts().get(0).getInstructions();
		assertThat(first.get(0).getText()).contains("Drip Fixers").contains("2026-10-10");
		assertThat(((ToolCallingChatOptions) model.prompts().get(0).getOptions()).getToolCallbacks())
				.extracting(t -> t.getToolDefinition().name()).containsExactly("find_property", "list_contractors");
		assertThat(model.prompts().get(1).getInstructions().getLast()).isInstanceOfSatisfying(ToolResponseMessage.class,
				r -> assertThat(r.getResponses().getFirst().responseData()).contains("P-7"));
	}

	@Test
	void theLoopStopsAtItsLimitAndTheLastTurnIsToldToAnswer() throws Exception {
		// The repairs agent allows 3 model calls; this model never stops asking.
		ScriptedChatModel model = new ScriptedChatModel(toolCall("c", "find_property", "{\"address\":\"x\"}"));

		Map<String, Object> update = run(model);

		assertThat(model.prompts()).hasSize(3);
		assertThat(model.prompts().get(2).getInstructions().getLast()).isInstanceOfSatisfying(UserMessage.class,
				m -> assertThat(m.getText()).isEqualTo(ToolCallingAgent.LAST_TURN));
		assertThat(context(update)).containsEntry("modelCalls", 3);
		assertThat((String) context(update).get("summary")).contains("Stopped after 3 model calls");
		assertThat(update.get(AuditEntry.CHANNEL).toString()).contains("stopped at the limit");
	}

	@Test
	void aToolOutsideTheAgentsListIsRefusedAndNeverSent() throws Exception {
		// book_visit writes: it is not the agent's, and the enrich node may not call it either.
		ScriptedChatModel model = new ScriptedChatModel(toolCall("c1", "book_visit", "{\"when\":\"now\"}"),
				new AssistantMessage("Could not book."));

		Map<String, Object> update = run(model);

		assertThat(sent).noneMatch(s -> s.startsWith("book_visit"));
		assertThat(lookups(update).getLast()).satisfies(l -> {
			assertThat(l.status()).isEqualTo("REFUSED");
			assertThat(l.message()).contains("not one of this agent's tools");
		});
		assertThat(model.prompts().get(1).getInstructions().getLast()).isInstanceOfSatisfying(ToolResponseMessage.class,
				r -> assertThat(r.getResponses().getFirst().responseData()).startsWith("REFUSED"));
	}

	@Test
	void aToolTheNodesAllowlistLacksIsBlockedByTheGatewayAndNotOffered() throws Exception {
		// The agent lists list_contractors, but this tools.yaml only lets enrich call find_property.
		ToolCatalog narrow = new ToolCatalog(Map.of(
				"find_property", new ToolDefinition("find_property", "landlord", "find_property", ToolAccess.READ),
				"list_contractors", new ToolDefinition("list_contractors", "landlord", "list_contractors", ToolAccess.READ)),
				Map.of("enrich", List.of("find_property")), java.util.Set.of());
		ScriptedChatModel model = new ScriptedChatModel(toolCall("c1", "list_contractors", "{\"trade\":\"PLUMBING\"}"),
				new AssistantMessage("Done."));

		Map<String, Object> update = run(model, narrow, Map.of("defects", List.of(Map.of("trade", "PLUMBING"))));

		assertThat(sent).isEmpty();
		assertThat(lookups(update)).extracting(Lookup::status).containsExactly("BLOCKED", "BLOCKED");
		assertThat(((ToolCallingChatOptions) model.prompts().get(0).getOptions()).getToolCallbacks())
				.extracting(t -> t.getToolDefinition().name()).containsExactly("find_property");
	}

	@Test
	void aPlanStepMissingAnArgumentIsSkippedNotSent() throws Exception {
		Map<String, Object> defect = new HashMap<>();
		defect.put("trade", null);

		Map<String, Object> update = run(new ScriptedChatModel("Nothing to add."), RepairsPack.tools(),
				Map.of("defects", List.of(defect)));

		assertThat(sent).isEmpty();
		assertThat(lookups(update)).singleElement().satisfies(l -> {
			assertThat(l.status()).isEqualTo("SKIPPED");
			assertThat(l.message()).isEqualTo("No value for [trade]");
		});
	}

	@Test
	void aToolErrorIsRecordedAndShownToTheModelNotFatal() throws Exception {
		ScriptedChatModel model = new ScriptedChatModel(toolCall("c1", "find_property", "not json"),
				new AssistantMessage("The lookup failed."));

		Map<String, Object> update = run(model);

		assertThat(lookups(update).getLast().status()).isEqualTo("TOOL_ERROR");
		assertThat(context(update)).containsEntry("summary", "The lookup failed.");
	}
}

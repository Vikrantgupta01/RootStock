package com.rootstock.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.rootstock.core.agent.dto.AgentStep;
import com.rootstock.core.chat.AiUnavailableException;
import com.rootstock.core.rag.RagProperties;
import com.rootstock.core.rag.document.ActiveVersionResolver;
import com.rootstock.core.rag.document.DocumentRepository;
import com.rootstock.core.rag.tenant.TenantContext;
import com.rootstock.core.rag.vector.BedrockKnowledgeBaseClient;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The ReAct loop against a scripted model: no Bedrock, no Spring context.
 * Tools are the real {@link AgentTools}, with the knowledge-base side mocked
 * out -- these runs only use {@code currentDateTime}.
 */
class AgentGraphTest {

	private final List<Prompt> prompts = new ArrayList<>();
	private final List<Observation> observedParents = new ArrayList<>();
	private final ObservationRegistry observations = ObservationRegistry.create();

	@BeforeEach
	void bindTenant() {
		TenantContext.set("t1");
		observations.observationConfig().observationHandler(context -> true);
	}

	@AfterEach
	void clearTenant() {
		TenantContext.clear();
	}

	@Test
	void reasonsActsObservesThenAnswers() {
		// Bedrock reports text and tool calls as separate generations; the graph
		// must see them as one turn.
		AgentService agent = agent(6, scripted(
				new ChatResponse(List.of(
						new Generation(new AssistantMessage("I need today's date first.")),
						new Generation(AssistantMessage.builder().content("")
								.toolCalls(List.of(call("c1", "currentDateTime"))).build()))),
				reply("Today is a fine day.")));

		Observation request = Observation.start("request", observations);
		AgentService.Result result;
		try (Observation.Scope ignored = request.openScope()) {
			result = agent.run(List.of(), "What day is it?");
		}
		finally {
			request.stop();
		}

		assertThat(result.answer()).isEqualTo("Today is a fine day.");
		assertThat(result.iterations()).isEqualTo(2);
		assertThat(result.steps()).singleElement().satisfies(step -> {
			assertThat(step.iteration()).isEqualTo(1);
			assertThat(step.tool()).isEqualTo("currentDateTime");
			assertThat(step.thought()).isEqualTo("I need today's date first.");
			assertThat(step.observation()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
		});

		// Reason step 1 saw the system prompt, the question, and the tool definitions.
		Prompt first = prompts.get(0);
		assertThat(first.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
		assertThat(first.getInstructions().get(1)).isInstanceOf(UserMessage.class);
		assertThat(((ToolCallingChatOptions) first.getOptions()).getToolCallbacks())
				.extracting(c -> c.getToolDefinition().name())
				.containsExactlyInAnyOrder("currentDateTime", "searchKnowledgeBase");

		// Reason step 2 observed the tool result, answered to the right call id.
		List<Message> second = prompts.get(1).getInstructions();
		assertThat(second.get(second.size() - 1)).isInstanceOfSatisfying(ToolResponseMessage.class,
				tr -> assertThat(tr.getResponses()).singleElement()
						.satisfies(r -> assertThat(r.id()).isEqualTo("c1")));

		// Nodes run on a pool thread; the request's observation must still be
		// current there, or each generation would start a trace of its own.
		assertThat(observedParents).hasSize(2).containsOnly(request);
	}

	@Test
	void answersDirectlyWithoutTools() {
		AgentService agent = agent(6, scripted(reply("Hello!")));

		AgentService.Result result = agent.run(List.of(), "Hi");

		assertThat(result.answer()).isEqualTo("Hello!");
		assertThat(result.iterations()).isEqualTo(1);
		assertThat(result.steps()).isEmpty();
	}

	@Test
	void stopsAtMaxIterationsAndWarnsTheLastStep() {
		AgentService agent = agent(2, prompt -> toolRequest("again"));

		AgentService.Result result = agent.run(List.of(), "Loop forever");

		assertThat(result.iterations()).isEqualTo(2);
		// Only the first request ran; the second came on the last step and was cut off.
		assertThat(result.steps()).hasSize(1);
		assertThat(result.answer()).isEqualTo(AgentService.NO_ANSWER);
		assertThat(prompts.get(1).getSystemMessage().getText()).contains("This is your last step");
	}

	@Test
	void unknownToolBecomesAnObservationNotAFailure() {
		AgentService agent = agent(6, scripted(
				new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
						.toolCalls(List.of(call("c1", "launchRockets"))).build()))),
				reply("I can't do that.")));

		AgentService.Result result = agent.run(List.of(), "Launch");

		assertThat(result.steps()).extracting(AgentStep::observation)
				.singleElement().asString().startsWith("Error: there is no tool named 'launchRockets'");
		assertThat(result.answer()).isEqualTo("I can't do that.");
	}

	@Test
	void missingChatBackendIsAiUnavailable() throws Exception {
		AgentService agent = new AgentService(new AgentGraph(
				new StaticListableBeanFactory().getBeanProvider(ChatModel.class), toolbox(), properties(6)));

		assertThatThrownBy(() -> agent.run(List.of(), "Hi"))
				.isInstanceOf(AiUnavailableException.class)
				.hasMessageContaining("No chat backend");
	}

	// ---- fixtures ------------------------------------------------------------

	private AgentService agent(int maxIterations, ChatModel model) {
		try {
			return new AgentService(new AgentGraph(
					new StaticListableBeanFactory(Map.of("chatModel", recording(model))).getBeanProvider(ChatModel.class),
					toolbox(), properties(maxIterations)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private ChatModel scripted(ChatResponse... responses) {
		Iterator<ChatResponse> script = List.of(responses).iterator();
		return prompt -> script.next();
	}

	/** Records what each Reason step was sent before answering it. */
	private ChatModel recording(ChatModel model) {
		return prompt -> {
			prompts.add(prompt);
			observedParents.add(observations.getCurrentObservation());
			return model.call(prompt);
		};
	}

	private static ChatResponse reply(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

	private static ChatResponse toolRequest(String id) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
				.toolCalls(List.of(call(id, "currentDateTime"))).build())));
	}

	private static AssistantMessage.ToolCall call(String id, String name) {
		return new AssistantMessage.ToolCall(id, "function", name, "{}");
	}

	private static AgentToolbox toolbox() {
		return new AgentToolbox(new AgentTools(mock(ActiveVersionResolver.class),
				mock(BedrockKnowledgeBaseClient.class), mock(DocumentRepository.class),
				new RagProperties(null, null, null, null)));
	}

	private static AgentProperties properties(int maxIterations) {
		return new AgentProperties(maxIterations, AgentProperties.DEFAULT_SYSTEM_PROMPT);
	}
}

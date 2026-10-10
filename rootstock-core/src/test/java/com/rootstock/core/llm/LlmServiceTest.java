package com.rootstock.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootstock.core.chat.AiUnavailableException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class LlmServiceTest {

	private static final List<PromptTemplate.Part> HELLO = List.of(new PromptTemplate.Part("system", "Be brief."),
			new PromptTemplate.Part("user", "Hello"));

	@Test
	void callsTheModelWithTheProfilesSettings() {
		ScriptedChatModel model = new ScriptedChatModel("Hi.");

		String reply = model.service().call("extraction", HELLO, null, Duration.ofSeconds(5));

		assertThat(reply).isEqualTo("Hi.");
		Prompt sent = model.prompts().getFirst();
		assertThat(sent.getInstructions()).hasExactlyElementsOfTypes(SystemMessage.class, UserMessage.class);
		assertThat(sent.getOptions().getModel()).isEqualTo("test-model");
		assertThat(sent.getOptions().getTemperature()).isEqualTo(0.0);
		assertThat(sent.getOptions().getMaxTokens()).isEqualTo(1000);
	}

	@Test
	void anUnknownProfileIsRefused() {
		assertThatThrownBy(() -> new ScriptedChatModel("x").service().call("poetry", HELLO, null, Duration.ofSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("poetry")
				.hasMessageContaining("extraction");
	}

	@Test
	void noModelConfiguredIsAnUnavailableAi() {
		LlmService llm = new LlmService(new StaticListableBeanFactory().getBeanProvider(ChatModel.class),
				Map.of("extraction", new ModelProfile(null, null, null)));

		assertThatThrownBy(() -> llm.call("extraction", HELLO, null, Duration.ofSeconds(1)))
				.isInstanceOf(AiUnavailableException.class);
	}

	@Test
	void aSlowModelTimesOut() {
		ScriptedChatModel model = new ScriptedChatModel("late").delay(Duration.ofSeconds(5));

		assertThatThrownBy(() -> model.service().call("extraction", HELLO, null, Duration.ofMillis(100)))
				.isInstanceOf(AiUnavailableException.class).hasMessageContaining("did not answer");
	}

	@Test
	void theCallSeesItsPromptAndTheCallersObservation() {
		ObservationRegistry observations = ObservationRegistry.create();
		observations.observationConfig().observationHandler(context -> true);
		List<Optional<PromptTemplate>> promptSeen = new ArrayList<>();
		List<Observation> parentSeen = new ArrayList<>();
		ChatModel model = new ChatModel() {

			@Override
			public ChatResponse call(Prompt prompt) {
				promptSeen.add(LlmService.promptInUse());
				parentSeen.add(observations.getCurrentObservation());
				return new ScriptedChatModel("ok").call(prompt);
			}
		};
		LlmService llm = new LlmService(new StaticListableBeanFactory(Map.of("m", model)).getBeanProvider(ChatModel.class),
				Map.of("extraction", new ModelProfile(null, null, null)));
		PromptTemplate prompt = new PromptTemplate("p", "production", 3, PromptTemplate.Source.LANGFUSE, HELLO);

		Observation node = Observation.start("node", observations);
		try (Observation.Scope ignored = node.openScope()) {
			llm.call("extraction", HELLO, prompt, Duration.ofSeconds(5));
		}
		finally {
			node.stop();
		}

		// The call runs on a worker thread, yet nests under the node and names its prompt.
		assertThat(promptSeen).containsExactly(Optional.of(prompt));
		assertThat(parentSeen).containsExactly(node);
		assertThat(LlmService.promptInUse()).isEmpty();
	}
}

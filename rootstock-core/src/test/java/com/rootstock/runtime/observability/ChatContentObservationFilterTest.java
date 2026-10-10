package com.rootstock.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring AI's own "prompt content" and "completion" observation handlers only
 * call {@code Log.info(...)} -- they never touch the span. This filter is the
 * only reason a Langfuse generation has any input or output at all, so it is
 * worth a test that would notice if it quietly stopped contributing.
 */
class ChatContentObservationFilterTest {

	private final ChatContentObservationFilter filter = new ChatContentObservationFilter(new ObjectMapper());

	@Test
	void promptAndCompletionBecomeSpanAttributes() {
		ChatModelObservationContext context = contextWith(
				new Prompt(List.of(
						new SystemMessage("You answer from context only."),
						new UserMessage("How long is the warranty?"))),
				"The warranty is 24 months [1].");

		filter.map(context);

		// A JSON message array, not one concatenated blob: Langfuse renders
		// {role, content} as a readable thread, which matters when a prompt
		// carries 20 replayed turns plus retrieved chunks.
		assertThat(valueOf(context, LangfuseAttributes.PROMPT))
				.isEqualTo("[{\"role\":\"system\",\"content\":\"You answer from context only.\"},"
						+ "{\"role\":\"user\",\"content\":\"How long is the warranty?\"}]");
		assertThat(valueOf(context, LangfuseAttributes.COMPLETION))
				.isEqualTo("[{\"role\":\"assistant\",\"content\":\"The warranty is 24 months [1].\"}]");
		// Langfuse would infer this from the model attribute, but an explicit
		// type always wins over inference and cannot drift.
		assertThat(valueOf(context, LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("generation");
	}

	@Test
	void anUnfinishedCallContributesNoCompletion() {
		ChatModelObservationContext context = contextWith(new Prompt(List.of(new UserMessage("hi"))), null);

		filter.map(context);

		assertThat(valueOf(context, LangfuseAttributes.PROMPT)).contains("\"hi\"");
		// Absent rather than an empty string: an empty output in Langfuse reads as
		// "the model returned nothing", which is a different fact from "the call
		// did not get that far".
		assertThat(valueOf(context, LangfuseAttributes.COMPLETION)).isNull();
	}

	@Test
	void anythingThatIsNotAChatObservationPassesThroughUntouched() {
		Observation.Context other = new Observation.Context();

		assertThat(filter.map(other)).isSameAs(other);
		assertThat(other.getHighCardinalityKeyValues()).isEmpty();
	}

	private static ChatModelObservationContext contextWith(Prompt prompt, String completion) {
		ChatModelObservationContext context = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider("test")
				.build();
		if (completion != null) {
			context.setResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(completion)))));
		}
		return context;
	}

	private static String valueOf(Observation.Context context, String key) {
		return context.getHighCardinalityKeyValues().stream()
				.filter(kv -> kv.getKey().equals(key))
				.map(KeyValue::getValue)
				.findFirst()
				.orElse(null);
	}
}

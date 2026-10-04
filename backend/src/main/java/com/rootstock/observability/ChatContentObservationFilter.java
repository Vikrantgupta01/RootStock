package com.rootstock.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Puts the prompt and the completion onto Spring AI's generation span.
 *
 * <p>This is not optional decoration. Spring AI ships
 * {@code ChatModelPromptContentObservationHandler} and
 * {@code ChatModelCompletionObservationHandler}, and despite the names their
 * {@code onStop} does one thing: {@code Log.info(...)}. Neither touches the
 * span. So {@code spring.ai.chat.observations.log-prompt=true} copies content
 * into the application log and still leaves every Langfuse generation with
 * empty input and output -- traces that tell you a call happened and cost 900
 * tokens, but not what was asked or answered. Langfuse's own Spring AI guide
 * says the same: a filter like this one is required.
 *
 * <p>Content is emitted as a JSON array of {@code {role, content}} objects
 * rather than the concatenated string the upstream example uses, because
 * Langfuse renders that shape as a readable message thread instead of one wall
 * of text -- which matters here, where a prompt carries up to twenty replayed
 * conversation turns plus retrieved document chunks.
 */
@Component
@ConditionalOnProperty(prefix = "rootstock.observability.langfuse", name = "enabled")
public class ChatContentObservationFilter implements ObservationFilter {

	private static final Logger log = LoggerFactory.getLogger(ChatContentObservationFilter.class);

	private final ObjectMapper json;

	public ChatContentObservationFilter(ObjectMapper json) {
		this.json = json;
	}

	@Override
	public Observation.Context map(Observation.Context context) {
		if (!(context instanceof ChatModelObservationContext chat)) {
			return context;
		}
		add(chat, LangfuseAttributes.PROMPT, render(promptMessages(chat)));
		add(chat, LangfuseAttributes.COMPLETION, render(completionMessages(chat)));
		// Langfuse would infer "generation" from the model attribute anyway, but an
		// explicit type always wins over inference and cannot drift.
		add(chat, LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_GENERATION);
		return chat;
	}

	private List<Map<String, String>> promptMessages(ChatModelObservationContext context) {
		if (context.getRequest() == null || CollectionUtils.isEmpty(context.getRequest().getInstructions())) {
			return List.of();
		}
		List<Map<String, String>> messages = new ArrayList<>();
		for (Message message : context.getRequest().getInstructions()) {
			if (StringUtils.hasText(message.getText())) {
				messages.add(Map.of("role", roleOf(message), "content", message.getText()));
			}
		}
		return messages;
	}

	private List<Map<String, String>> completionMessages(ChatModelObservationContext context) {
		if (context.getResponse() == null || CollectionUtils.isEmpty(context.getResponse().getResults())) {
			return List.of();
		}
		List<Map<String, String>> messages = new ArrayList<>();
		for (Generation generation : context.getResponse().getResults()) {
			if (generation.getOutput() != null && StringUtils.hasText(generation.getOutput().getText())) {
				messages.add(Map.of("role", "assistant", "content", generation.getOutput().getText()));
			}
		}
		return messages;
	}

	/** Spring AI's message types already line up with the role names Langfuse expects. */
	private static String roleOf(Message message) {
		return message.getMessageType() == null ? "user" : message.getMessageType().getValue();
	}

	private String render(List<Map<String, String>> messages) {
		if (messages.isEmpty()) {
			return null;
		}
		try {
			return json.writeValueAsString(messages);
		}
		catch (RuntimeException ex) {
			// Never let a tracing concern break the call it is observing.
			log.debug("Could not serialize chat content for tracing", ex);
			return null;
		}
	}

	private static void add(ChatModelObservationContext context, String key, String value) {
		if (value != null) {
			context.addHighCardinalityKeyValue(KeyValue.of(key, value));
		}
	}
}

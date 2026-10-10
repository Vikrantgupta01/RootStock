package com.rootstock.core.llm;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** A chat model that replies with the given texts in turn and remembers every prompt. No Bedrock. */
public final class ScriptedChatModel implements ChatModel {

	private final Deque<String> replies;
	private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
	private Duration delay = Duration.ZERO;

	public ScriptedChatModel(String... replies) {
		this.replies = new ArrayDeque<>(Arrays.asList(replies));
	}

	public ScriptedChatModel delay(Duration delay) {
		this.delay = delay;
		return this;
	}

	@Override
	public synchronized ChatResponse call(Prompt prompt) {
		prompts.add(prompt);
		if (!delay.isZero()) {
			try {
				Thread.sleep(delay);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		String reply = replies.size() > 1 ? replies.poll() : replies.peek();
		return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
	}

	public List<Prompt> prompts() {
		return prompts;
	}

	public ObjectProvider<ChatModel> provider() {
		return new StaticListableBeanFactory(Map.of("chatModel", this)).getBeanProvider(ChatModel.class);
	}

	/** An LlmService over this model with an {@code extraction} profile. */
	public LlmService service() {
		return new LlmService(provider(), Map.of("extraction", new ModelProfile("test-model", 0.0, 1000)));
	}
}

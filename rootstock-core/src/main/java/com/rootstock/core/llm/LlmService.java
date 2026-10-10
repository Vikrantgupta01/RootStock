package com.rootstock.core.llm;

import com.rootstock.core.chat.AiUnavailableException;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * How case agents call a model. An agent names a {@link ModelProfile}
 * ({@code extraction}, {@code fast}, {@code drafting}…), never a model id, so
 * models are routed by task in configuration. Each call has a time limit and
 * says which prompt it used, so the trace can link the generation to its
 * prompt version.
 */
public final class LlmService implements AutoCloseable {

	private static final ThreadLocal<PromptTemplate> PROMPT_IN_USE = new ThreadLocal<>();
	private static final ContextSnapshotFactory CONTEXT = ContextSnapshotFactory.builder().build();

	private final ObjectProvider<ChatModel> models;
	private final Map<String, ModelProfile> profiles;
	private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

	public LlmService(ObjectProvider<ChatModel> models, Map<String, ModelProfile> profiles) {
		this.models = models;
		this.profiles = Map.copyOf(profiles);
	}

	public Set<String> profiles() {
		return new TreeSet<>(profiles.keySet());
	}

	public Optional<ModelProfile> profile(String name) {
		return Optional.ofNullable(profiles.get(name));
	}

	/**
	 * The prompt of the model call running on this thread, if any; read by
	 * tracing while it records the generation.
	 */
	public static Optional<PromptTemplate> promptInUse() {
		return Optional.ofNullable(PROMPT_IN_USE.get());
	}

	/**
	 * One model call; returns the reply's text.
	 *
	 * @param prompt  the prompt the messages were rendered from, for tracing; may be null
	 * @param timeout how long to wait before giving up
	 * @throws AiUnavailableException when there is no model, it fails, or it takes too long
	 */
	public String call(String profile, List<PromptTemplate.Part> messages, PromptTemplate prompt, Duration timeout) {
		ModelProfile p = profiles.get(profile);
		if (p == null) {
			throw new IllegalArgumentException("Unknown model profile '" + profile + "'; configured: " + profiles());
		}
		ChatModel model = models.getIfAvailable();
		if (model == null) {
			throw new AiUnavailableException("No chat backend is configured. Provide AWS Bedrock credentials and "
					+ "set spring.ai.model.chat=bedrock-converse.");
		}
		Prompt request = new Prompt(messages(messages), options(model.getDefaultOptions(), p));
		// On a worker thread, so a hung call can be abandoned, but in the caller's
		// observation context, so the generation nests under the calling node's span.
		ContextSnapshot context = CONTEXT.captureAll();
		Future<ChatResponse> call = calls.submit(context.wrap(() -> {
			PROMPT_IN_USE.set(prompt);
			try {
				return model.call(request);
			}
			finally {
				PROMPT_IN_USE.remove();
			}
		}));
		try {
			return text(call.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
		}
		catch (TimeoutException e) {
			call.cancel(true);
			throw new AiUnavailableException("The model did not answer within " + timeout.toSeconds() + "s", e);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof AiUnavailableException a ? a
					: new AiUnavailableException("The AI provider failed: " + cause.getMessage(), cause);
		}
		catch (InterruptedException e) {
			call.cancel(true);
			Thread.currentThread().interrupt();
			throw new AiUnavailableException("Interrupted while waiting for the model", e);
		}
	}

	private static ChatOptions options(ChatOptions defaults, ModelProfile p) {
		// Starting from the model's own defaults keeps anything Bedrock-specific.
		ChatOptions.Builder<?> builder = defaults == null ? ChatOptions.builder() : defaults.mutate();
		if (p.model() != null && !p.model().isBlank()) {
			builder.model(p.model());
		}
		if (p.temperature() != null) {
			builder.temperature(p.temperature());
		}
		if (p.maxTokens() != null) {
			builder.maxTokens(p.maxTokens());
		}
		return builder.build();
	}

	private static List<Message> messages(List<PromptTemplate.Part> parts) {
		List<Message> out = new ArrayList<>();
		for (PromptTemplate.Part p : parts) {
			out.add(switch (p.role()) {
				case "system" -> new SystemMessage(p.content());
				case "assistant" -> new AssistantMessage(p.content());
				default -> new UserMessage(p.content());
			});
		}
		return out;
	}

	/** Bedrock may split a reply over several generations; join their text. */
	private static String text(ChatResponse response) {
		StringBuilder text = new StringBuilder();
		if (response != null) {
			for (Generation g : response.getResults()) {
				if (g.getOutput() != null && g.getOutput().getText() != null) {
					text.append(g.getOutput().getText());
				}
			}
		}
		return text.toString();
	}

	@Override
	public void close() {
		calls.shutdownNow();
	}
}

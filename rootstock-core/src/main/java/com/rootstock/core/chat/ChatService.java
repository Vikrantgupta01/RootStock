package com.rootstock.core.chat;

import com.rootstock.core.config.RootStockProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Thin wrapper over the Spring AI {@link ChatClient}. Keeps controllers free of
 * AI-specific types and gives a single place to add memory, RAG advisors,
 * guardrails, etc. later.
 *
 * <p>The {@link ChatClient} is built lazily from the auto-configured
 * {@link ChatClient.Builder} (backed by AWS Bedrock Converse). When no chat model
 * is configured -- {@code spring.ai.model.chat=none}, or the Bedrock starter is
 * absent -- there is no builder and requests fail with {@link AiUnavailableException}
 * (HTTP 503) instead of the application refusing to start.
 */
@Service
public class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);

	private final ObjectProvider<ChatClient.Builder> builderProvider;
	private final RootStockProperties properties;

	private volatile ChatClient chatClient;
	private volatile ChatClient bareClient;

	public ChatService(ObjectProvider<ChatClient.Builder> builderProvider, RootStockProperties properties) {
		this.builderProvider = builderProvider;
		this.properties = properties;
	}

	/**
	 * Answers {@code message} in the context of {@code history} -- the earlier
	 * turns of the same conversation, oldest first. An empty history is a fresh
	 * conversation, which is what every request used to be.
	 */
	public String reply(List<Message> history, String message) {
		ChatClient client = client();
		try {
			return client.prompt().messages(history).user(message).call().content();
		}
		catch (RuntimeException ex) {
			log.error("Chat request to the AI provider failed", ex);
			throw new AiUnavailableException("The AI provider could not be reached.", ex);
		}
	}

	public Flux<String> replyStream(List<Message> history, String message) {
		return client().prompt().messages(history).user(message).stream().content();
	}

	/**
	 * One-off completion with a caller-supplied system and user prompt (used by
	 * RAG, which composes its own context-grounded prompt per profile).
	 */
	public String generate(String systemPrompt, String userPrompt) {
		return generate(systemPrompt, List.of(), userPrompt);
	}

	/** As above, with the earlier turns of the same conversation (oldest first). */
	public String generate(String systemPrompt, List<Message> history, String userPrompt) {
		ChatClient client = bareClient();
		try {
			return client.prompt().system(systemPrompt).messages(history).user(userPrompt).call().content();
		}
		catch (RuntimeException ex) {
			log.error("Chat request to the AI provider failed", ex);
			throw new AiUnavailableException("The AI provider could not be reached.", ex);
		}
	}

	private ChatClient bareClient() {
		ChatClient existing = this.bareClient;
		if (existing != null) {
			return existing;
		}
		ChatClient built = requireBuilder().build();
		this.bareClient = built;
		return built;
	}

	private ChatClient client() {
		ChatClient existing = this.chatClient;
		if (existing != null) {
			return existing;
		}
		ChatClient built = requireBuilder().defaultSystem(properties.chat().systemPrompt()).build();
		this.chatClient = built;
		return built;
	}

	private ChatClient.Builder requireBuilder() {
		ChatClient.Builder builder = builderProvider.getIfAvailable();
		if (builder == null) {
			throw new AiUnavailableException(
					"No chat backend is configured. Provide AWS Bedrock credentials and "
							+ "set spring.ai.model.chat=bedrock-converse.");
		}
		return builder;
	}
}

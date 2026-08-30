package com.rootstock.chat;

import com.rootstock.config.RootStockProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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

	public ChatService(ObjectProvider<ChatClient.Builder> builderProvider, RootStockProperties properties) {
		this.builderProvider = builderProvider;
		this.properties = properties;
	}

	public String reply(String message) {
		ChatClient client = client();
		try {
			return client.prompt().user(message).call().content();
		}
		catch (RuntimeException ex) {
			log.error("Chat request to the AI provider failed", ex);
			throw new AiUnavailableException("The AI provider could not be reached.", ex);
		}
	}

	public Flux<String> replyStream(String message) {
		return client().prompt().user(message).stream().content();
	}

	private ChatClient client() {
		ChatClient existing = this.chatClient;
		if (existing != null) {
			return existing;
		}
		ChatClient.Builder builder = builderProvider.getIfAvailable();
		if (builder == null) {
			throw new AiUnavailableException(
					"No chat backend is configured. Provide AWS Bedrock credentials and "
							+ "set spring.ai.model.chat=bedrock-converse.");
		}
		ChatClient built = builder.defaultSystem(properties.chat().systemPrompt()).build();
		this.chatClient = built;
		return built;
	}
}

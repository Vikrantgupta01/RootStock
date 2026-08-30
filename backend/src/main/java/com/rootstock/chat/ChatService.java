package com.rootstock.chat;

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
 */
@Service
public class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);

	private final ObjectProvider<ChatClient> chatClient;

	public ChatService(ObjectProvider<ChatClient> chatClient) {
		this.chatClient = chatClient;
	}

	public String reply(String message) {
		ChatClient client = requireClient();
		try {
			return client.prompt().user(message).call().content();
		}
		catch (RuntimeException ex) {
			log.error("Chat request to the AI provider failed", ex);
			throw new AiUnavailableException("The AI provider could not be reached.", ex);
		}
	}

	public Flux<String> replyStream(String message) {
		ChatClient client = requireClient();
		return client.prompt().user(message).stream().content();
	}

	private ChatClient requireClient() {
		ChatClient client = chatClient.getIfAvailable();
		if (client == null) {
			throw new AiUnavailableException(
					"No chat backend is configured. Set AWS Bedrock credentials and "
							+ "spring.ai.model.chat=bedrock-converse.");
		}
		return client;
	}
}

package com.rootstock.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the application-wide {@link ChatClient} from the auto-configured
 * {@link ChatClient.Builder} (backed by AWS Bedrock Converse).
 *
 * <p>The bean is conditional: when {@code spring.ai.model.chat=none} there is no
 * builder on the context and the application still starts -- {@code ChatService}
 * then reports the AI backend as unavailable.
 */
@Configuration(proxyBeanMethods = false)
public class AiConfig {

	@Bean
	@ConditionalOnBean(ChatClient.Builder.class)
	ChatClient chatClient(ChatClient.Builder builder, RootStockProperties properties) {
		return builder
				.defaultSystem(properties.chat().systemPrompt())
				.build();
	}
}

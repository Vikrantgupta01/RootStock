package com.rootstock.core.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for the {@code rootstock.*} configuration namespace.
 */
@ConfigurationProperties(prefix = "rootstock")
public record RootStockProperties(Cors cors, Chat chat) {

	public RootStockProperties {
		if (cors == null) {
			cors = new Cors(List.of("http://localhost:5173"));
		}
		if (chat == null) {
			chat = new Chat("You are RootStock's assistant. Be concise, accurate, and helpful.");
		}
	}

	public record Cors(List<String> allowedOrigins) {
		public Cors {
			if (allowedOrigins == null || allowedOrigins.isEmpty()) {
				allowedOrigins = List.of("http://localhost:5173");
			}
		}
	}

	public record Chat(String systemPrompt) {
	}
}

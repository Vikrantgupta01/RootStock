package com.rootstock.core.chat;

/**
 * Raised when a chat request arrives but no chat backend is configured
 * (for example {@code spring.ai.model.chat=none}, or AWS Bedrock credentials
 * are absent). Mapped to HTTP 503 by the global exception handler.
 */
public class AiUnavailableException extends RuntimeException {

	public AiUnavailableException(String message) {
		super(message);
	}

	public AiUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}

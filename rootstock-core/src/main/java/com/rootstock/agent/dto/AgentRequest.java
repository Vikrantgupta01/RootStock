package com.rootstock.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param conversationId the thread to continue; {@code null} starts a new one,
 *                       and the response says which id to send next time
 */
public record AgentRequest(
		@NotBlank @Size(max = 8_000) String message,
		UUID conversationId) {
}

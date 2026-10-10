package com.rootstock.core.agent.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param steps every tool call the agent made on the way to {@code answer}, in
 *              order; empty when it answered directly
 */
public record AgentResponse(String answer, UUID conversationId, int iterations, List<AgentStep> steps) {
}

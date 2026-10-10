package com.rootstock.core.agent;

import com.rootstock.core.agent.dto.AgentStep;
import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.chat.AiUnavailableException;
import com.rootstock.core.rag.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Runs one question through the {@link AgentGraph}. Keeps the controller free of
 * graph types, and is the one place a run's failures become a 503.
 */
@Service
public class AgentService {

	private static final Logger log = LoggerFactory.getLogger(AgentService.class);

	static final String NO_ANSWER = "I wasn't able to reach an answer within the steps I'm allowed. "
			+ "Try asking a narrower question.";

	private final AgentGraph graph;

	public AgentService(AgentGraph graph) {
		this.graph = graph;
	}

	/** What a run produced: the answer, and how the agent got there. */
	public record Result(String answer, int iterations, List<AgentStep> steps) {
	}

	/**
	 * @param history earlier turns of the same conversation, oldest first
	 */
	public Result run(List<Message> history, String message) {
		List<Message> transcript = new ArrayList<>(history);
		transcript.add(new UserMessage(message));

		ReActState end;
		try {
			end = graph.run(transcript, toolContext());
		}
		catch (RuntimeException ex) {
			// LangGraph4j may wrap what a node threw; a missing backend is still a
			// missing backend, with its own message.
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof AiUnavailableException unavailable) {
					throw unavailable;
				}
			}
			log.error("Agent run failed", ex);
			throw new AiUnavailableException("The AI provider could not be reached.", ex);
		}

		String answer = end.lastAssistant()
				.map(AssistantMessage::getText)
				.filter(StringUtils::hasText)
				.orElse(NO_ANSWER);
		return new Result(answer, end.iterations(), end.steps());
	}

	/** Captured here, on the request thread, where the ThreadLocals are bound. */
	private static Map<String, Object> toolContext() {
		Map<String, Object> context = new HashMap<>();
		context.put(AgentTools.TENANT_ID, TenantContext.require());
		List<String> groups = AuthContext.retrievalGroupsOrNull();
		if (groups != null) {
			context.put(AgentTools.RETRIEVAL_GROUPS, groups);
		}
		return context;
	}
}

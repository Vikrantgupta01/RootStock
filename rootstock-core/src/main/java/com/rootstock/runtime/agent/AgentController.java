package com.rootstock.runtime.agent;

import com.rootstock.core.agent.AgentService;
import com.rootstock.core.agent.dto.AgentRequest;
import com.rootstock.core.agent.dto.AgentResponse;
import com.rootstock.core.conversation.ChatMessage;
import com.rootstock.core.conversation.Conversation;
import com.rootstock.core.conversation.ConversationKind;
import com.rootstock.core.conversation.ConversationService;
import com.rootstock.core.conversation.dto.ConversationSummary;
import com.rootstock.core.conversation.dto.MessageResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ReAct agent. Conversations work as in {@code /api/chat}: send back the
 * {@code conversationId} to continue a thread. Only the question and the final
 * answer are kept in history -- the tool calls in between are the working of
 * one answer, returned as {@code steps}, not part of the dialogue.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

	private final AgentService agent;
	private final ConversationService conversations;

	public AgentController(AgentService agent, ConversationService conversations) {
		this.agent = agent;
		this.conversations = conversations;
	}

	@PostMapping
	public AgentResponse ask(@Valid @RequestBody AgentRequest request) {
		Conversation conversation = conversations.resolve(
				request.conversationId(), ConversationKind.AGENT, request.message());
		AgentService.Result result = agent.run(
				ConversationService.toPromptMessages(conversations.history(conversation.getId())),
				request.message());
		conversations.append(conversation, request.message(), result.answer());
		return new AgentResponse(result.answer(), conversation.getId(), result.iterations(), result.steps());
	}

	@GetMapping("/conversations")
	public List<ConversationSummary> list() {
		return conversations.list(ConversationKind.AGENT).stream().map(ConversationSummary::of).toList();
	}

	@GetMapping("/conversations/{id}")
	public List<MessageResponse> transcript(@PathVariable UUID id) {
		List<ChatMessage> messages = conversations.transcript(id);
		return messages.stream().map(MessageResponse::of).toList();
	}

	@DeleteMapping("/conversations/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		conversations.delete(id);
	}
}

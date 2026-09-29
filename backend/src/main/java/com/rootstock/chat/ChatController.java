package com.rootstock.chat;

import com.rootstock.chat.dto.ChatRequest;
import com.rootstock.chat.dto.ChatResponse;
import com.rootstock.conversation.ChatMessage;
import com.rootstock.conversation.Conversation;
import com.rootstock.conversation.ConversationKind;
import com.rootstock.conversation.ConversationService;
import com.rootstock.conversation.dto.ConversationSummary;
import com.rootstock.conversation.dto.MessageResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * The plain assistant. Each call continues a conversation the backend owns:
 * send the {@code conversationId} from the previous response to keep the thread,
 * or omit it to start a new one.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private final ChatService chatService;
	private final ConversationService conversations;

	public ChatController(ChatService chatService, ConversationService conversations) {
		this.chatService = chatService;
		this.conversations = conversations;
	}

	@PostMapping
	public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
		Conversation conversation = conversations.resolve(
				request.conversationId(), ConversationKind.CHAT, request.message());
		String reply = chatService.reply(
				ConversationService.toPromptMessages(conversations.history(conversation.getId())),
				request.message());
		conversations.append(conversation, request.message(), reply);
		return new ChatResponse(reply, conversation.getId());
	}

	@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> chatStream(@Valid @RequestBody ChatRequest request) {
		Conversation conversation = conversations.resolve(
				request.conversationId(), ConversationKind.CHAT, request.message());
		// History is read here, on the request thread, because the tenant and user
		// live in ThreadLocals that the reactive completion thread won't have.
		// `conversation` is a value by then, so appending later needs neither.
		var history = ConversationService.toPromptMessages(conversations.history(conversation.getId()));
		StringBuilder answer = new StringBuilder();
		return chatService.replyStream(history, request.message())
				.doOnNext(answer::append)
				.doOnComplete(() -> conversations.append(conversation, request.message(), answer.toString()));
	}

	@GetMapping("/conversations")
	public List<ConversationSummary> list() {
		return conversations.list(ConversationKind.CHAT).stream().map(ConversationSummary::of).toList();
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

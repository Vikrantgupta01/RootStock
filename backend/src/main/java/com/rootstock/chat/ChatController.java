package com.rootstock.chat;

import com.rootstock.chat.dto.ChatRequest;
import com.rootstock.chat.dto.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Sample AI endpoint exercising the AWS Bedrock chat path end to end.
 * Replace with real domain endpoints as the product takes shape.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private final ChatService chatService;

	public ChatController(ChatService chatService) {
		this.chatService = chatService;
	}

	@PostMapping
	public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
		return new ChatResponse(chatService.reply(request.message()));
	}

	@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> chatStream(@Valid @RequestBody ChatRequest request) {
		return chatService.replyStream(request.message());
	}
}

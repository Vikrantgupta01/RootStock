package com.rootstock.runtime.rag.query;

import com.rootstock.core.conversation.ConversationKind;
import com.rootstock.core.conversation.ConversationService;
import com.rootstock.core.conversation.dto.ConversationSummary;
import com.rootstock.core.conversation.dto.MessageResponse;
import com.rootstock.core.rag.query.RagQueryService;
import com.rootstock.core.rag.query.dto.RagQueryRequest;
import com.rootstock.core.rag.query.dto.RagQueryResponse;
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
 * Retrieval-augmented question answering over a tenant's indexed documents.
 * Threads are separate from {@code /api/chat}'s: send back the
 * {@code conversationId} from a response to ask a follow-up.
 */
@RestController
@RequestMapping("/api/rag")
public class RagQueryController {

	private final RagQueryService service;
	private final ConversationService conversations;

	public RagQueryController(RagQueryService service, ConversationService conversations) {
		this.service = service;
		this.conversations = conversations;
	}

	@PostMapping("/query")
	public RagQueryResponse query(@Valid @RequestBody RagQueryRequest request) {
		return service.query(request);
	}

	@GetMapping("/conversations")
	public List<ConversationSummary> list() {
		return conversations.list(ConversationKind.RAG).stream().map(ConversationSummary::of).toList();
	}

	@GetMapping("/conversations/{id}")
	public List<MessageResponse> transcript(@PathVariable UUID id) {
		return conversations.transcript(id).stream().map(MessageResponse::of).toList();
	}

	@DeleteMapping("/conversations/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		conversations.delete(id);
	}
}

package com.rootstock.conversation;

import com.rootstock.auth.AuthContext;
import com.rootstock.common.ResourceNotFoundException;
import com.rootstock.rag.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns conversation history: starting threads, reading back the window that
 * goes to the model, and appending each completed turn.
 *
 * <p>Every lookup is by (id, tenant, user) together, so a conversation id
 * guessed or copied from somewhere else resolves to nothing rather than to
 * someone else's thread.
 */
@Service
public class ConversationService {

	/**
	 * How many past turns ride along with a new message. A cap, not a nicety:
	 * history grows without bound, every turn is re-sent on every request, and
	 * Bedrock charges for all of it -- an uncapped thread would slowly turn into
	 * an expensive request that eventually exceeds the model's context window.
	 */
	public static final int HISTORY_TURNS = 20;

	private final ConversationRepository conversations;
	private final ChatMessageRepository messages;

	public ConversationService(ConversationRepository conversations, ChatMessageRepository messages) {
		this.conversations = conversations;
		this.messages = messages;
	}

	@Transactional(readOnly = true)
	public List<Conversation> list(ConversationKind kind) {
		return conversations.findByTenantIdAndUserIdAndKindOrderByUpdatedAtDesc(
				TenantContext.require(), currentUserId(), kind);
	}

	@Transactional(readOnly = true)
	public Conversation require(UUID id) {
		return conversations.findByIdAndTenantIdAndUserId(id, TenantContext.require(), currentUserId())
				.orElseThrow(() -> ResourceNotFoundException.of("Conversation", id));
	}

	@Transactional(readOnly = true)
	public List<ChatMessage> transcript(UUID conversationId) {
		require(conversationId);
		return messages.findByConversationIdOrderBySeqAsc(conversationId);
	}

	/**
	 * Resolves the conversation a turn belongs to, starting one if the caller
	 * didn't name an existing thread.
	 *
	 * @param conversationId an existing thread, or {@code null} to start a new one
	 * @param openingMessage used as the title when a new thread is started
	 */
	@Transactional
	public Conversation resolve(UUID conversationId, ConversationKind kind, String openingMessage) {
		if (conversationId == null) {
			return conversations.save(new Conversation(TenantContext.require(), currentUserId(), kind,
					Conversation.titleFrom(openingMessage)));
		}
		Conversation conversation = require(conversationId);
		if (conversation.getKind() != kind) {
			// A RAG thread answers under a grounded prompt and a plain chat thread
			// doesn't; replaying one as the other would silently change the rules.
			throw new IllegalArgumentException(
					"Conversation " + conversationId + " is a " + conversation.getKind()
							+ " conversation and can't be continued as " + kind + ".");
		}
		return conversation;
	}

	/**
	 * The recent turns to send with the next message, oldest first. Truncated to
	 * {@link #HISTORY_TURNS}; the cut always lands on a turn boundary, never
	 * mid-exchange, so the model never sees a question whose answer was dropped.
	 */
	@Transactional(readOnly = true)
	public List<ChatMessage> history(UUID conversationId) {
		List<ChatMessage> newestFirst = messages.findByConversationIdOrderBySeqDesc(
				conversationId, Limit.of(HISTORY_TURNS));
		List<ChatMessage> oldestFirst = new ArrayList<>(newestFirst);
		java.util.Collections.reverse(oldestFirst);
		// Drop a leading assistant message: it would be an answer to a question
		// that fell outside the window, which reads as a non sequitur.
		if (!oldestFirst.isEmpty() && oldestFirst.get(0).getRole() == MessageRole.ASSISTANT) {
			return oldestFirst.subList(1, oldestFirst.size());
		}
		return oldestFirst;
	}

	/** Appends the completed exchange and bumps the thread's activity time. */
	@Transactional
	public void append(Conversation conversation, String question, String answer) {
		int next = (int) messages.countByConversationId(conversation.getId());
		messages.save(new ChatMessage(conversation.getId(), next, MessageRole.USER, question));
		messages.save(new ChatMessage(conversation.getId(), next + 1, MessageRole.ASSISTANT, answer));
		conversation.touch();
		conversations.save(conversation);
	}

	@Transactional
	public void delete(UUID conversationId) {
		conversations.delete(require(conversationId)); // chat_message rows cascade in the DB
	}

	/** Stored turns as Spring AI messages, ready to prepend to the next prompt. */
	public static List<Message> toPromptMessages(List<ChatMessage> history) {
		return history.stream()
				.map(m -> m.getRole() == MessageRole.USER
						? (Message) new UserMessage(m.getContent())
						: new AssistantMessage(m.getContent()))
				.toList();
	}

	private static String currentUserId() {
		return AuthContext.require().userId();
	}
}

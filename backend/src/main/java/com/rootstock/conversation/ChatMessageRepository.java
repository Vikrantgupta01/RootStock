package com.rootstock.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

	List<ChatMessage> findByConversationIdOrderBySeqAsc(UUID conversationId);

	/** The tail of the thread, newest first -- the window actually sent to the model. */
	List<ChatMessage> findByConversationIdOrderBySeqDesc(UUID conversationId, Limit limit);

	long countByConversationId(UUID conversationId);
}

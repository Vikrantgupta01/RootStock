package com.rootstock.conversation.dto;

import com.rootstock.conversation.ChatMessage;
import com.rootstock.conversation.MessageRole;
import java.time.Instant;
import java.util.UUID;

public record MessageResponse(UUID id, int seq, MessageRole role, String content, Instant createdAt) {

	public static MessageResponse of(ChatMessage m) {
		return new MessageResponse(m.getId(), m.getSeq(), m.getRole(), m.getContent(), m.getCreatedAt());
	}
}

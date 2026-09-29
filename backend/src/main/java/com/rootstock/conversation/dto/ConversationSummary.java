package com.rootstock.conversation.dto;

import com.rootstock.conversation.Conversation;
import java.time.Instant;
import java.util.UUID;

public record ConversationSummary(UUID id, String title, Instant createdAt, Instant updatedAt) {

	public static ConversationSummary of(Conversation c) {
		return new ConversationSummary(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt());
	}
}

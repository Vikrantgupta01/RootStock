package com.rootstock.core.chat.dto;

import java.util.UUID;

public record ChatResponse(String reply, UUID conversationId) {
}

package com.rootstock.rag.query.dto;

import java.util.List;
import java.util.UUID;

public record RagQueryResponse(
		String answer,
		boolean grounded,
		List<Citation> citations,
		UUID profileId,
		String profileName,
		int profileVersionNo,
		List<UUID> usedVersionIds) {
}

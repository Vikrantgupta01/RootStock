package com.rootstock.core.rag.query.dto;

import java.util.UUID;

public record Citation(
		int rank,
		UUID documentId,
		String sourceKey,
		String displayName,
		Integer versionNo,
		Integer chunkIndex,
		Double score,
		String snippet) {
}

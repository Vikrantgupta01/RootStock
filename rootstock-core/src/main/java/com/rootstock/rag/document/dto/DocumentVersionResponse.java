package com.rootstock.rag.document.dto;

import com.rootstock.rag.document.DocumentStatus;
import com.rootstock.rag.document.DocumentVersion;
import java.time.Instant;
import java.util.UUID;

public record DocumentVersionResponse(
		UUID id,
		int versionNo,
		DocumentStatus status,
		long sizeBytes,
		String contentHash,
		int chunkCount,
		boolean active,
		String errorMessage,
		Instant createdAt,
		Instant indexedAt) {

	public static DocumentVersionResponse of(DocumentVersion v, UUID activeVersionId) {
		return new DocumentVersionResponse(
				v.getId(),
				v.getVersionNo(),
				v.getStatus(),
				v.getSizeBytes(),
				v.getContentHash(),
				v.getChunkCount(),
				v.getId().equals(activeVersionId),
				v.getErrorMessage(),
				v.getCreatedAt(),
				v.getIndexedAt());
	}
}

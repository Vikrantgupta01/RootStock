package com.rootstock.rag.document.dto;

import com.rootstock.rag.document.Document;
import com.rootstock.rag.document.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

public record DocumentSummaryResponse(
		UUID id,
		String sourceKey,
		String displayName,
		String contentType,
		UUID activeVersionId,
		int versionCount,
		DocumentStatus activeStatus,
		Instant createdAt,
		Instant updatedAt) {

	public static DocumentSummaryResponse of(Document d, int versionCount, DocumentStatus activeStatus) {
		return new DocumentSummaryResponse(
				d.getId(),
				d.getSourceKey(),
				d.getDisplayName(),
				d.getContentType(),
				d.getActiveVersionId(),
				versionCount,
				activeStatus,
				d.getCreatedAt(),
				d.getUpdatedAt());
	}
}

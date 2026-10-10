package com.rootstock.core.rag.document.dto;

import com.rootstock.core.rag.access.AccessGroup;
import com.rootstock.core.rag.document.Document;
import com.rootstock.core.rag.document.DocumentStatus;
import java.time.Instant;
import java.util.List;
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
		Instant updatedAt,
		/** Groups allowed to retrieve this document; empty means the whole tenant. */
		List<String> accessGroups) {

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
				d.getUpdatedAt(),
				d.getAccessGroups().stream().map(AccessGroup::getName).sorted().toList());
	}
}

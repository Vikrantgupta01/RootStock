package com.rootstock.rag.document.dto;

import com.rootstock.rag.access.AccessGroup;
import com.rootstock.rag.document.Document;
import com.rootstock.rag.document.DocumentVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentDetailResponse(
		UUID id,
		String sourceKey,
		String displayName,
		String contentType,
		UUID activeVersionId,
		Instant createdAt,
		Instant updatedAt,
		/** Groups allowed to retrieve this document; empty means the whole tenant. */
		List<String> accessGroups,
		List<DocumentVersionResponse> versions) {

	public static DocumentDetailResponse of(Document d, List<DocumentVersion> versions) {
		List<DocumentVersionResponse> versionDtos = versions.stream()
				.map(v -> DocumentVersionResponse.of(v, d.getActiveVersionId()))
				.toList();
		return new DocumentDetailResponse(
				d.getId(),
				d.getSourceKey(),
				d.getDisplayName(),
				d.getContentType(),
				d.getActiveVersionId(),
				d.getCreatedAt(),
				d.getUpdatedAt(),
				d.getAccessGroups().stream().map(AccessGroup::getName).sorted().toList(),
				versionDtos);
	}
}

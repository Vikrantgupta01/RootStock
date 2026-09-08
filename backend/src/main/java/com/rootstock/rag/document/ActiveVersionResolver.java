package com.rootstock.rag.document;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves a tenant's set of active, fully-indexed document versions -- the
 * versions RAG retrieval and re-index run over.
 */
@Component
public class ActiveVersionResolver {

	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;

	public ActiveVersionResolver(DocumentRepository documents, DocumentVersionRepository versions) {
		this.documents = documents;
		this.versions = versions;
	}

	public List<DocumentVersion> activeIndexed(String tenantId) {
		List<UUID> activeIds = documents.findByTenantId(tenantId).stream()
				.map(Document::getActiveVersionId)
				.filter(Objects::nonNull)
				.toList();
		if (activeIds.isEmpty()) {
			return List.of();
		}
		return versions.findByTenantIdAndIdIn(tenantId, activeIds).stream()
				.filter(v -> v.getStatus() == DocumentStatus.INDEXED)
				.toList();
	}
}

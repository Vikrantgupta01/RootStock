package com.rootstock.rag.vector;

import java.util.List;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentruntime.model.FilterAttribute;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;

/**
 * Builds Bedrock Retrieve API filter expressions over the metadata attributes
 * every document carries (see {@link RagChunkMetadata}, set via the S3
 * {@code .metadata.json} sidecar at ingest time).
 */
public final class KnowledgeBaseFilters {

	private KnowledgeBaseFilters() {
	}

	/** {@code tenant_id = ? AND document_version_id IN (?)} -- the chunks a query may retrieve over. */
	public static RetrievalFilter retrieval(String tenantId, List<String> activeVersionIds) {
		RetrievalFilter tenantFilter = tenant(tenantId);
		if (activeVersionIds.isEmpty()) {
			return tenantFilter;
		}
		RetrievalFilter versionFilter = RetrievalFilter.builder()
				.in(FilterAttribute.builder()
						.key(RagChunkMetadata.DOCUMENT_VERSION_ID)
						.value(Document.fromList(activeVersionIds.stream().map(Document::fromString).toList()))
						.build())
				.build();
		return RetrievalFilter.builder().andAll(tenantFilter, versionFilter).build();
	}

	/** {@code tenant_id = ?} on its own. */
	public static RetrievalFilter tenant(String tenantId) {
		return RetrievalFilter.builder()
				.equalsValue(FilterAttribute.builder()
						.key(RagChunkMetadata.TENANT_ID)
						.value(Document.fromString(tenantId))
						.build())
				.build();
	}
}

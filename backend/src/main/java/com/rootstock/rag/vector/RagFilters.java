package com.rootstock.rag.vector;

import java.util.List;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op;

/**
 * Builds vector-store metadata filter expressions for RAG retrieval and cleanup.
 */
public final class RagFilters {

	private RagFilters() {
	}

	/** {@code tenant_id = ? AND profile_id = ? AND document_version_id IN (?)}. */
	public static Filter.Expression retrieval(String tenantId, String profileId, List<String> activeVersionIds) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		Op op = b.and(
				b.and(b.eq(RagChunkMetadata.TENANT_ID, tenantId), b.eq(RagChunkMetadata.PROFILE_ID, profileId)),
				b.in(RagChunkMetadata.DOCUMENT_VERSION_ID, activeVersionIds.toArray()));
		return op.build();
	}

	/** {@code tenant_id = ? AND document_version_id = ?} — used to purge one version's chunks. */
	public static Filter.Expression forVersion(String tenantId, String documentVersionId) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		return b.and(
				b.eq(RagChunkMetadata.TENANT_ID, tenantId),
				b.eq(RagChunkMetadata.DOCUMENT_VERSION_ID, documentVersionId))
				.build();
	}

	/** {@code tenant_id = ? AND profile_id = ?} — used to purge a superseded profile's chunks. */
	public static Filter.Expression forProfile(String tenantId, String profileId) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		return b.and(
				b.eq(RagChunkMetadata.TENANT_ID, tenantId),
				b.eq(RagChunkMetadata.PROFILE_ID, profileId))
				.build();
	}
}

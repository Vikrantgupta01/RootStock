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

	/**
	 * {@code tenant_id = ? AND embedding_model_id = ? AND chunk_config = ?
	 * AND document_version_id IN (?)} — the chunks a profile should retrieve over.
	 */
	public static Filter.Expression retrieval(String tenantId, String embeddingModelId, String chunkConfig,
			List<String> activeVersionIds) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		Op byProfile = b.and(
				b.eq(RagChunkMetadata.TENANT_ID, tenantId),
				b.and(b.eq(RagChunkMetadata.EMBEDDING_MODEL_ID, embeddingModelId),
						b.eq(RagChunkMetadata.CHUNK_CONFIG, chunkConfig)));
		return b.and(byProfile, b.in(RagChunkMetadata.DOCUMENT_VERSION_ID, activeVersionIds.toArray())).build();
	}

	/** {@code tenant_id = ? AND document_version_id = ?} — purge every chunk of one version. */
	public static Filter.Expression forVersion(String tenantId, String documentVersionId) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		return b.and(
				b.eq(RagChunkMetadata.TENANT_ID, tenantId),
				b.eq(RagChunkMetadata.DOCUMENT_VERSION_ID, documentVersionId))
				.build();
	}

	/**
	 * {@code tenant_id = ? AND document_version_id = ? AND embedding_model_id = ?
	 * AND chunk_config = ?} — the chunks one (re-)index run owns, so a retry does
	 * not disturb another profile's chunks for the same version.
	 */
	public static Filter.Expression forVersionAndChunkConfig(String tenantId, String documentVersionId,
			String embeddingModelId, String chunkConfig) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		Op idScope = b.and(
				b.eq(RagChunkMetadata.TENANT_ID, tenantId),
				b.eq(RagChunkMetadata.DOCUMENT_VERSION_ID, documentVersionId));
		Op layoutScope = b.and(
				b.eq(RagChunkMetadata.EMBEDDING_MODEL_ID, embeddingModelId),
				b.eq(RagChunkMetadata.CHUNK_CONFIG, chunkConfig));
		return b.and(idScope, layoutScope).build();
	}

	/**
	 * {@code tenant_id = ? AND embedding_model_id = ? AND chunk_config = ?} —
	 * purge the chunks a superseded profile layout produced.
	 */
	public static Filter.Expression forChunkConfig(String tenantId, String embeddingModelId, String chunkConfig) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		return b.and(
				b.eq(RagChunkMetadata.TENANT_ID, tenantId),
				b.and(b.eq(RagChunkMetadata.EMBEDDING_MODEL_ID, embeddingModelId),
						b.eq(RagChunkMetadata.CHUNK_CONFIG, chunkConfig)))
				.build();
	}
}

package com.rootstock.rag.vector;

/**
 * Metadata keys attached to every chunk written to the vector store.
 *
 * <p>Chunks are tagged with the {@code embedding_model_id} and {@code chunk_config}
 * that produced them (not a profile id), so retrieval can select exactly the
 * chunks a profile expects: a query-time-only profile change reuses existing
 * chunks, while a chunking/embedding change is served by a separate set built
 * under a blue/green re-index.
 */
public final class RagChunkMetadata {

	public static final String TENANT_ID = "tenant_id";
	public static final String DOCUMENT_ID = "document_id";
	public static final String DOCUMENT_VERSION_ID = "document_version_id";
	public static final String EMBEDDING_MODEL_ID = "embedding_model_id";
	public static final String CHUNK_CONFIG = "chunk_config";
	public static final String CHUNK_INDEX = "chunk_index";

	private RagChunkMetadata() {
	}
}

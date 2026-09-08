package com.rootstock.rag.vector;

/**
 * Metadata keys attached to every chunk written to the vector store. Retrieval
 * filters on these to isolate a tenant's active document versions under a
 * given profile.
 */
public final class RagChunkMetadata {

	public static final String TENANT_ID = "tenant_id";
	public static final String DOCUMENT_ID = "document_id";
	public static final String DOCUMENT_VERSION_ID = "document_version_id";
	public static final String PROFILE_ID = "profile_id";
	public static final String SOURCE_KEY = "source_key";
	public static final String CHUNK_INDEX = "chunk_index";

	private RagChunkMetadata() {
	}
}

package com.rootstock.rag.vector;

/**
 * Metadata attribute keys attached to every document via its S3
 * {@code .metadata.json} sidecar, and returned back on every Bedrock Retrieve
 * result. Used to scope retrieval to a tenant (and its currently-active document
 * versions) and to resolve a hit back to its {@code Document}/{@code DocumentVersion}
 * row.
 */
public final class RagChunkMetadata {

	public static final String TENANT_ID = "tenant_id";
	public static final String DOCUMENT_ID = "document_id";
	public static final String DOCUMENT_VERSION_ID = "document_version_id";

	private RagChunkMetadata() {
	}
}

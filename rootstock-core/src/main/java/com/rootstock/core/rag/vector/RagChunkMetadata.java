package com.rootstock.core.rag.vector;

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

	/**
	 * The access groups allowed to retrieve this document, as a {@code STRING_LIST}.
	 * Always non-empty: a document with no explicit grants carries
	 * {@link com.rootstock.core.auth.AuthContext#PUBLIC_GROUP} so "visible to the whole
	 * tenant" is a value that can be matched rather than an absent attribute.
	 */
	public static final String ACCESS_GROUPS = "access_groups";

	private RagChunkMetadata() {
	}
}

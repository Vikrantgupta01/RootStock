package com.rootstock.rag.document;

public enum DocumentStatus {
	/** Uploaded, waiting for the ingestion poller. */
	PENDING,
	/** Being parsed / embedded. */
	PROCESSING,
	/** Chunks are in the vector store; queryable when this version is active. */
	INDEXED,
	/** Ingestion failed after exhausting retries; see {@code errorMessage}. */
	FAILED,
	/** Chunks removed (a newer version replaced it and cleanup ran). */
	SUPERSEDED
}

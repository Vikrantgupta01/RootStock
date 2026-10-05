package com.rootstock.rag.ingest;

public enum IngestionJobKind {
	/** Parse, chunk, embed and index a document version for the first time. */
	INGEST,
	/** Re-embed an already-uploaded version (after failure, or a profile change). */
	REINDEX,
	/** Delete a superseded version's or profile's chunks from the vector store. */
	CLEANUP
}

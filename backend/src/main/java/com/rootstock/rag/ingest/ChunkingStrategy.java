package com.rootstock.rag.ingest;

/**
 * How a parsed document's text is broken into chunks before embedding.
 *
 * <p>Only {@link #CHARACTER} is implemented today (a sliding character window with
 * overlap that honours {@code chunkSize}/{@code chunkOverlap}). {@link #TOKEN} and
 * {@link #SEMANTIC} are reserved for later work and currently fall back to
 * {@code CHARACTER}.
 */
public enum ChunkingStrategy {
	CHARACTER,
	TOKEN,
	SEMANTIC
}

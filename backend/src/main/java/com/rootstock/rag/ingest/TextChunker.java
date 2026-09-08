package com.rootstock.rag.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into overlapping windows. Sizes are in characters. The window tries
 * to end on a paragraph/sentence/word boundary near {@code size} to avoid cutting
 * mid-word; consecutive windows overlap by {@code overlap} characters.
 */
public final class TextChunker {

	private static final char[] BOUNDARY_PREFS = {'\n', '.', '!', '?', ' '};

	private TextChunker() {
	}

	public static List<String> chunk(String text, ChunkingStrategy strategy, int size, int overlap) {
		// TOKEN / SEMANTIC not implemented yet -- treat as CHARACTER.
		String normalized = text == null ? "" : text.strip();
		List<String> chunks = new ArrayList<>();
		if (normalized.isEmpty()) {
			return chunks;
		}
		int effectiveSize = Math.max(200, size);
		int effectiveOverlap = Math.max(0, Math.min(overlap, effectiveSize / 2));
		int stride = Math.max(1, effectiveSize - effectiveOverlap);

		int start = 0;
		int length = normalized.length();
		while (start < length) {
			int hardEnd = Math.min(length, start + effectiveSize);
			int end = hardEnd < length ? boundaryBefore(normalized, start, hardEnd) : hardEnd;
			String piece = normalized.substring(start, end).strip();
			if (!piece.isEmpty()) {
				chunks.add(piece);
			}
			if (end >= length) {
				break;
			}
			start = Math.max(end - effectiveOverlap, start + stride);
		}
		return chunks;
	}

	private static int boundaryBefore(String text, int start, int hardEnd) {
		int minEnd = start + (hardEnd - start) / 2; // don't shrink a window below half
		for (char pref : BOUNDARY_PREFS) {
			int idx = text.lastIndexOf(pref, hardEnd - 1);
			if (idx >= minEnd) {
				return idx + 1;
			}
		}
		return hardEnd;
	}
}

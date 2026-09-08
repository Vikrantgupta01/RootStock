package com.rootstock.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

	@Test
	void shortTextIsOneChunk() {
		List<String> chunks = TextChunker.chunk("Just a short sentence.", ChunkingStrategy.CHARACTER, 1200, 150);
		assertThat(chunks).containsExactly("Just a short sentence.");
	}

	@Test
	void emptyTextYieldsNoChunks() {
		assertThat(TextChunker.chunk("   ", ChunkingStrategy.CHARACTER, 1200, 150)).isEmpty();
	}

	@Test
	void longTextIsSplitIntoOverlappingWindows() {
		String sentence = "The quick brown fox jumps over the lazy dog. ";
		String text = sentence.repeat(60); // ~2700 chars

		List<String> chunks = TextChunker.chunk(text, ChunkingStrategy.CHARACTER, 800, 200);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(800));

		// consecutive windows share a tail/head (overlap)
		String tailOfFirst = chunks.get(0).substring(chunks.get(0).length() - 50);
		assertThat(chunks.get(1)).contains(tailOfFirst.strip().split(" ")[0]);

		// reassembling without overlap covers the whole text
		assertThat(String.join(" ", chunks)).contains("quick brown fox");
	}

	@Test
	void tokenStrategyFallsBackToCharacterWindowing() {
		String text = "word ".repeat(500);
		List<String> character = TextChunker.chunk(text, ChunkingStrategy.CHARACTER, 500, 50);
		List<String> token = TextChunker.chunk(text, ChunkingStrategy.TOKEN, 500, 50);
		assertThat(token).hasSameSizeAs(character);
	}
}

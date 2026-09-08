package com.rootstock.rag.profile.dto;

import com.rootstock.rag.ingest.ChunkingStrategy;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creates a new named profile at version 1 (inactive). Null fields fall back to
 * {@code rootstock.rag.defaults}.
 */
public record CreateRagProfileRequest(
		@NotBlank @Size(max = 128) String name,
		ChunkingStrategy chunkingStrategy,
		@Min(200) @Max(8000) Integer chunkSize,
		@Min(0) @Max(2000) Integer chunkOverlap,
		@Size(max = 64) String embeddingModelId,
		@Size(max = 256) String chatModelId,
		@Min(1) @Max(50) Integer topK,
		@DecimalMin("0.0") @DecimalMax("1.0") Double similarityThreshold,
		Boolean rerankerEnabled,
		@Size(max = 128) String rerankerModel,
		@Min(256) @Max(200_000) Integer maxContextTokens,
		@Size(max = 20_000) String promptTemplate,
		Boolean hybridSearch) {
}

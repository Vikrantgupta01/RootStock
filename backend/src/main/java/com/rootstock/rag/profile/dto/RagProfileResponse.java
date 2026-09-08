package com.rootstock.rag.profile.dto;

import com.rootstock.rag.ingest.ChunkingStrategy;
import com.rootstock.rag.profile.RagProfile;
import java.time.Instant;
import java.util.UUID;

public record RagProfileResponse(
		UUID id,
		String name,
		int versionNo,
		boolean active,
		ChunkingStrategy chunkingStrategy,
		int chunkSize,
		int chunkOverlap,
		String embeddingModelId,
		String chatModelId,
		int topK,
		double similarityThreshold,
		boolean rerankerEnabled,
		String rerankerModel,
		int maxContextTokens,
		String promptTemplate,
		boolean hybridSearch,
		String fineTunedModelArn,
		Instant createdAt) {

	public static RagProfileResponse of(RagProfile p) {
		return new RagProfileResponse(
				p.getId(), p.getName(), p.getVersionNo(), p.isActive(),
				p.getChunkingStrategy(), p.getChunkSize(), p.getChunkOverlap(),
				p.getEmbeddingModelId(), p.getChatModelId(), p.getTopK(), p.getSimilarityThreshold(),
				p.isRerankerEnabled(), p.getRerankerModel(), p.getMaxContextTokens(),
				p.getPromptTemplate(), p.isHybridSearch(), p.getFineTunedModelArn(), p.getCreatedAt());
	}
}

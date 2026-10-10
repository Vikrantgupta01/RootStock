package com.rootstock.core.rag.profile.dto;

import com.rootstock.core.rag.profile.RagProfile;
import java.time.Instant;
import java.util.UUID;

public record RagProfileResponse(
		UUID id,
		String name,
		int versionNo,
		boolean active,
		String chatModelId,
		int topK,
		double similarityThreshold,
		boolean rerankerEnabled,
		String rerankerModel,
		int maxContextTokens,
		String promptTemplate,
		String fineTunedModelArn,
		Instant createdAt) {

	public static RagProfileResponse of(RagProfile p) {
		return new RagProfileResponse(
				p.getId(), p.getName(), p.getVersionNo(), p.isActive(),
				p.getChatModelId(), p.getTopK(), p.getSimilarityThreshold(),
				p.isRerankerEnabled(), p.getRerankerModel(), p.getMaxContextTokens(),
				p.getPromptTemplate(), p.getFineTunedModelArn(), p.getCreatedAt());
	}
}

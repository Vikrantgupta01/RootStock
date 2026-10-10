package com.rootstock.core.rag.profile.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Produces a new version of an existing profile. Every field is optional; a null
 * field is copied unchanged from the version being edited.
 */
public record UpdateRagProfileRequest(
		@Size(max = 256) String chatModelId,
		@Min(1) @Max(50) Integer topK,
		@DecimalMin("0.0") @DecimalMax("1.0") Double similarityThreshold,
		Boolean rerankerEnabled,
		@Size(max = 128) String rerankerModel,
		@Min(256) @Max(200_000) Integer maxContextTokens,
		@Size(max = 20_000) String promptTemplate) {
}

package com.rootstock.rag.profile.dto;

import com.rootstock.rag.profile.RagProfileActivation;
import java.time.Instant;
import java.util.UUID;

public record ActivationResponse(
		UUID activationId,
		UUID targetProfileId,
		UUID previousProfileId,
		boolean reindexRequired,
		int totalReindexJobs,
		RagProfileActivation.State state,
		String errorMessage,
		Instant createdAt,
		Instant completedAt) {

	/** Immediate activation (no re-index needed). */
	public static ActivationResponse immediate(UUID targetProfileId, UUID previousProfileId) {
		return new ActivationResponse(null, targetProfileId, previousProfileId, false, 0,
				RagProfileActivation.State.COMPLETED, null, Instant.now(), Instant.now());
	}

	public static ActivationResponse of(RagProfileActivation a) {
		return new ActivationResponse(a.getId(), a.getTargetProfileId(), a.getPreviousProfileId(),
				true, a.getTotalJobs(), a.getState(), a.getErrorMessage(), a.getCreatedAt(), a.getCompletedAt());
	}
}

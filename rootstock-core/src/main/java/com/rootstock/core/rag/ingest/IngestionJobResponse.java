package com.rootstock.core.rag.ingest;

import java.time.Instant;
import java.util.UUID;

public record IngestionJobResponse(
		UUID id,
		IngestionJobKind kind,
		IngestionJobState state,
		UUID documentVersionId,
		UUID profileId,
		int attempts,
		int maxAttempts,
		String lockedBy,
		String errorMessage,
		Instant createdAt,
		Instant updatedAt) {

	public static IngestionJobResponse of(IngestionJob j) {
		return new IngestionJobResponse(
				j.getId(), j.getKind(), j.getState(), j.getDocumentVersionId(), j.getProfileId(),
				j.getAttempts(), j.getMaxAttempts(), j.getLockedBy(), j.getErrorMessage(),
				j.getCreatedAt(), j.getUpdatedAt());
	}
}

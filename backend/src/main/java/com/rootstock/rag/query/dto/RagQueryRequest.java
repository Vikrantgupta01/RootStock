package com.rootstock.rag.query.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A retrieval-augmented question. {@code profileId} defaults to the active
 * profile; {@code topK} / {@code similarityThreshold} override the profile's
 * values for this call only.
 */
public record RagQueryRequest(
		@NotBlank @Size(max = 4000) String question,
		UUID profileId,
		@Min(1) @Max(50) Integer topK,
		@DecimalMin("0.0") @DecimalMax("1.0") Double similarityThreshold) {
}

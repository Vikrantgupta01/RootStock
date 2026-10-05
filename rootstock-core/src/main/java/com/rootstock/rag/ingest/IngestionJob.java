package com.rootstock.rag.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A unit of background work in the ingestion ledger. The poller claims
 * {@code QUEUED} rows with {@code FOR UPDATE SKIP LOCKED}, so multiple app
 * instances can share the queue safely.
 */
@Entity
@Table(name = "ingestion_job")
@EntityListeners(AuditingEntityListener.class)
public class IngestionJob {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, length = 128, updatable = false)
	private String tenantId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16, updatable = false)
	private IngestionJobKind kind;

	@Column(name = "document_version_id", updatable = false)
	private UUID documentVersionId;

	@Column(name = "profile_id", updatable = false)
	private UUID profileId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private IngestionJobState state = IngestionJobState.QUEUED;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "max_attempts", nullable = false)
	private int maxAttempts = 3;

	@Column(name = "locked_by", length = 128)
	private String lockedBy;

	@Column(name = "locked_at")
	private Instant lockedAt;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected IngestionJob() {
	}

	public IngestionJob(String tenantId, IngestionJobKind kind, UUID documentVersionId, UUID profileId,
			int maxAttempts) {
		this.tenantId = tenantId;
		this.kind = kind;
		this.documentVersionId = documentVersionId;
		this.profileId = profileId;
		this.maxAttempts = maxAttempts;
	}

	public UUID getId() {
		return id;
	}

	public String getTenantId() {
		return tenantId;
	}

	public IngestionJobKind getKind() {
		return kind;
	}

	public UUID getDocumentVersionId() {
		return documentVersionId;
	}

	public UUID getProfileId() {
		return profileId;
	}

	public IngestionJobState getState() {
		return state;
	}

	public void setState(IngestionJobState state) {
		this.state = state;
	}

	public int getAttempts() {
		return attempts;
	}

	public void incrementAttempts() {
		this.attempts++;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public String getLockedBy() {
		return lockedBy;
	}

	public void setLockedBy(String lockedBy) {
		this.lockedBy = lockedBy;
	}

	public Instant getLockedAt() {
		return lockedAt;
	}

	public void setLockedAt(Instant lockedAt) {
		this.lockedAt = lockedAt;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}

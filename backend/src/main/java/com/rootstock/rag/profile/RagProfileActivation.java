package com.rootstock.rag.profile;

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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A pending or completed blue/green profile switch. While {@code PENDING} the
 * {@code previousProfileId} stays active; the monitor flips to
 * {@code targetProfileId} once all its re-index jobs succeed.
 */
@Entity
@Table(name = "rag_profile_activation")
@EntityListeners(AuditingEntityListener.class)
public class RagProfileActivation {

	public enum State {
		PENDING,
		COMPLETED,
		FAILED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, length = 128, updatable = false)
	private String tenantId;

	@Column(name = "target_profile_id", nullable = false, updatable = false)
	private UUID targetProfileId;

	@Column(name = "previous_profile_id", updatable = false)
	private UUID previousProfileId;

	@Column(name = "total_jobs", nullable = false, updatable = false)
	private int totalJobs;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private State state = State.PENDING;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected RagProfileActivation() {
	}

	public RagProfileActivation(String tenantId, UUID targetProfileId, UUID previousProfileId, int totalJobs) {
		this.tenantId = tenantId;
		this.targetProfileId = targetProfileId;
		this.previousProfileId = previousProfileId;
		this.totalJobs = totalJobs;
	}

	public UUID getId() {
		return id;
	}

	public String getTenantId() {
		return tenantId;
	}

	public UUID getTargetProfileId() {
		return targetProfileId;
	}

	public UUID getPreviousProfileId() {
		return previousProfileId;
	}

	public int getTotalJobs() {
		return totalJobs;
	}

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
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

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}
}

package com.rootstock.rag.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * A logical document in a tenant's knowledge base, identified by
 * {@code sourceKey} (usually the uploaded filename). Its bytes live in one or
 * more {@link DocumentVersion}s; {@code activeVersionId} points at the version
 * used for retrieval.
 */
@Entity
@Table(name = "document")
@EntityListeners(AuditingEntityListener.class)
public class Document {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, length = 128, updatable = false)
	private String tenantId;

	@Column(name = "source_key", nullable = false, length = 512, updatable = false)
	private String sourceKey;

	@Column(name = "display_name", nullable = false, length = 512)
	private String displayName;

	@Column(name = "content_type", length = 255)
	private String contentType;

	@Column(name = "active_version_id")
	private UUID activeVersionId;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Document() {
	}

	public Document(String tenantId, String sourceKey, String displayName, String contentType) {
		this.tenantId = tenantId;
		this.sourceKey = sourceKey;
		this.displayName = displayName;
		this.contentType = contentType;
	}

	public UUID getId() {
		return id;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getSourceKey() {
		return sourceKey;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public UUID getActiveVersionId() {
		return activeVersionId;
	}

	public void setActiveVersionId(UUID activeVersionId) {
		this.activeVersionId = activeVersionId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}

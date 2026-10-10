package com.rootstock.core.rag.document;

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
 * One uploaded revision of a {@link Document}. Immutable bytes (referenced by
 * {@code blobKey}); {@code status} tracks the ingestion lifecycle.
 */
@Entity
@Table(name = "document_version")
@EntityListeners(AuditingEntityListener.class)
public class DocumentVersion {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "document_id", nullable = false, updatable = false)
	private UUID documentId;

	@Column(name = "tenant_id", nullable = false, length = 128, updatable = false)
	private String tenantId;

	@Column(name = "version_no", nullable = false, updatable = false)
	private int versionNo;

	@Column(name = "blob_key", nullable = false, length = 128, updatable = false)
	private String blobKey;

	@Column(name = "content_hash", nullable = false, length = 64, updatable = false)
	private String contentHash;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private long sizeBytes;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private DocumentStatus status = DocumentStatus.PENDING;

	@Column(name = "chunk_count", nullable = false)
	private int chunkCount;

	@Column(name = "error_message", length = 2000)
	private String errorMessage;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "indexed_at")
	private Instant indexedAt;

	protected DocumentVersion() {
	}

	public DocumentVersion(UUID documentId, String tenantId, int versionNo, String blobKey, String contentHash,
			long sizeBytes) {
		this.documentId = documentId;
		this.tenantId = tenantId;
		this.versionNo = versionNo;
		this.blobKey = blobKey;
		this.contentHash = contentHash;
		this.sizeBytes = sizeBytes;
	}

	public UUID getId() {
		return id;
	}

	public UUID getDocumentId() {
		return documentId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public int getVersionNo() {
		return versionNo;
	}

	public String getBlobKey() {
		return blobKey;
	}

	public String getContentHash() {
		return contentHash;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}

	public DocumentStatus getStatus() {
		return status;
	}

	public void setStatus(DocumentStatus status) {
		this.status = status;
	}

	public int getChunkCount() {
		return chunkCount;
	}

	public void setChunkCount(int chunkCount) {
		this.chunkCount = chunkCount;
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

	public Instant getIndexedAt() {
		return indexedAt;
	}

	public void setIndexedAt(Instant indexedAt) {
		this.indexedAt = indexedAt;
	}
}

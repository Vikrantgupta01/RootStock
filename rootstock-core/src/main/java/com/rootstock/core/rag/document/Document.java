package com.rootstock.core.rag.document;

import com.rootstock.core.rag.access.AccessGroup;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A logical document in a tenant's knowledge base, identified by
 * {@code sourceKey} (usually the uploaded filename). Its bytes live in one or
 * more {@link DocumentVersion}s; {@code activeVersionId} points at the version
 * used for retrieval.
 *
 * <p>{@code accessGroups} restricts who may retrieve it: empty means everyone in
 * the tenant. {@code metadata} is free-form, locally queryable document metadata;
 * both are mirrored into the S3 {@code .metadata.json} sidecar Bedrock filters
 * on, but this row is the source of truth for them.
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

	/** Empty = visible to everyone in the tenant. */
	@ManyToMany(fetch = FetchType.EAGER)
	@JoinTable(name = "document_access_group",
			joinColumns = @JoinColumn(name = "document_id"),
			inverseJoinColumns = @JoinColumn(name = "group_id"))
	private Set<AccessGroup> accessGroups = new LinkedHashSet<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> metadata = new HashMap<>();

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

	public Set<AccessGroup> getAccessGroups() {
		return accessGroups;
	}

	public void setAccessGroups(Set<AccessGroup> accessGroups) {
		this.accessGroups = accessGroups != null ? accessGroups : new LinkedHashSet<>();
	}

	public Map<String, Object> getMetadata() {
		return metadata;
	}

	public void setMetadata(Map<String, Object> metadata) {
		this.metadata = metadata != null ? metadata : new HashMap<>();
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}

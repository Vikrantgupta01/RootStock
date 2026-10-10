package com.rootstock.core.rag.access;

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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A group documents can be restricted to, mirroring a Cognito user pool group of
 * the same name.
 *
 * <p>Cognito owns <em>membership</em> — who is in this group arrives as the
 * signed {@code cognito:groups} claim on each request, never from this table.
 * This row exists so a document can be tagged against a group with referential
 * integrity, and so the admin UI can list the groups available to tag with
 * without calling Cognito on every render.
 */
@Entity
@Table(name = "access_group")
@EntityListeners(AuditingEntityListener.class)
public class AccessGroup {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, length = 128, updatable = false)
	private String tenantId;

	@Column(nullable = false, length = 128, updatable = false)
	private String name;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AccessGroup() {
	}

	public AccessGroup(String tenantId, String name) {
		this.tenantId = tenantId;
		this.name = name;
	}

	public UUID getId() {
		return id;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}

package com.rootstock.conversation;

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
 * A thread of messages belonging to one person. Owned by the pair
 * (tenant, Cognito {@code sub}) -- there is no sharing model for chat, so a
 * conversation is never visible to anyone but its author.
 */
@Entity
@Table(name = "conversation")
@EntityListeners(AuditingEntityListener.class)
public class Conversation {

	/** Titles are cut from the opening question; long enough to be recognisable in a list. */
	private static final int TITLE_MAX = 120;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, length = 128, updatable = false)
	private String tenantId;

	@Column(name = "user_id", nullable = false, length = 128, updatable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16, updatable = false)
	private ConversationKind kind;

	@Column(length = 512)
	private String title;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Conversation() {
	}

	public Conversation(String tenantId, String userId, ConversationKind kind, String title) {
		this.tenantId = tenantId;
		this.userId = userId;
		this.kind = kind;
		this.title = title;
	}

	/** A readable label for the conversation list, taken from its opening message. */
	public static String titleFrom(String firstMessage) {
		String collapsed = firstMessage.strip().replaceAll("\\s+", " ");
		return collapsed.length() <= TITLE_MAX ? collapsed : collapsed.substring(0, TITLE_MAX - 1) + "…";
	}

	public UUID getId() {
		return id;
	}

	public String getTenantId() {
		return tenantId;
	}

	public String getUserId() {
		return userId;
	}

	public ConversationKind getKind() {
		return kind;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** Bumps {@code updatedAt} so the conversation list orders by real activity. */
	public void touch() {
		this.updatedAt = Instant.now();
	}
}

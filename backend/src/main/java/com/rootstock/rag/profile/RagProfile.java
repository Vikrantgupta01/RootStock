package com.rootstock.rag.profile;

import com.rootstock.rag.ingest.ChunkingStrategy;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * An immutable, versioned bundle of RAG settings for one tenant. Editing a
 * profile inserts a new row with {@code versionNo + 1}; exactly one row per
 * tenant has {@code active = true} (enforced by a partial unique index).
 */
@Entity
@Table(name = "rag_profile")
@EntityListeners(AuditingEntityListener.class)
public class RagProfile {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, length = 128, updatable = false)
	private String tenantId;

	@Column(nullable = false, length = 128, updatable = false)
	private String name;

	@Column(name = "version_no", nullable = false, updatable = false)
	private int versionNo;

	@Column(nullable = false)
	private boolean active;

	@Enumerated(EnumType.STRING)
	@Column(name = "chunking_strategy", nullable = false, length = 32, updatable = false)
	private ChunkingStrategy chunkingStrategy;

	@Column(name = "chunk_size", nullable = false, updatable = false)
	private int chunkSize;

	@Column(name = "chunk_overlap", nullable = false, updatable = false)
	private int chunkOverlap;

	@Column(name = "embedding_model_id", nullable = false, length = 64, updatable = false)
	private String embeddingModelId;

	@Column(name = "chat_model_id", length = 256, updatable = false)
	private String chatModelId;

	@Column(name = "top_k", nullable = false)
	private int topK;

	@Column(name = "similarity_threshold", nullable = false)
	private double similarityThreshold;

	@Column(name = "reranker_enabled", nullable = false)
	private boolean rerankerEnabled;

	@Column(name = "reranker_model", length = 128)
	private String rerankerModel;

	@Column(name = "max_context_tokens", nullable = false)
	private int maxContextTokens;

	@JdbcTypeCode(SqlTypes.LONGVARCHAR)
	@Column(name = "prompt_template", nullable = false)
	private String promptTemplate;

	@Column(name = "hybrid_search", nullable = false)
	private boolean hybridSearch;

	@Column(name = "fine_tuned_model_arn", length = 512, updatable = false)
	private String fineTunedModelArn;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "created_by", length = 128, updatable = false)
	private String createdBy;

	protected RagProfile() {
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

	public int getVersionNo() {
		return versionNo;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public ChunkingStrategy getChunkingStrategy() {
		return chunkingStrategy;
	}

	public int getChunkSize() {
		return chunkSize;
	}

	public int getChunkOverlap() {
		return chunkOverlap;
	}

	public String getEmbeddingModelId() {
		return embeddingModelId;
	}

	public String getChatModelId() {
		return chatModelId;
	}

	public int getTopK() {
		return topK;
	}

	public double getSimilarityThreshold() {
		return similarityThreshold;
	}

	public boolean isRerankerEnabled() {
		return rerankerEnabled;
	}

	public String getRerankerModel() {
		return rerankerModel;
	}

	public int getMaxContextTokens() {
		return maxContextTokens;
	}

	public String getPromptTemplate() {
		return promptTemplate;
	}

	public boolean isHybridSearch() {
		return hybridSearch;
	}

	public String getFineTunedModelArn() {
		return fineTunedModelArn;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	/** Fluent builder used for seeding and (phase 2) creating new versions. */
	public static final class Builder {

		private final RagProfile p = new RagProfile();

		public Builder tenantId(String v) { p.tenantId = v; return this; }

		public Builder name(String v) { p.name = v; return this; }

		public Builder versionNo(int v) { p.versionNo = v; return this; }

		public Builder active(boolean v) { p.active = v; return this; }

		public Builder chunkingStrategy(ChunkingStrategy v) { p.chunkingStrategy = v; return this; }

		public Builder chunkSize(int v) { p.chunkSize = v; return this; }

		public Builder chunkOverlap(int v) { p.chunkOverlap = v; return this; }

		public Builder embeddingModelId(String v) { p.embeddingModelId = v; return this; }

		public Builder chatModelId(String v) { p.chatModelId = v; return this; }

		public Builder topK(int v) { p.topK = v; return this; }

		public Builder similarityThreshold(double v) { p.similarityThreshold = v; return this; }

		public Builder rerankerEnabled(boolean v) { p.rerankerEnabled = v; return this; }

		public Builder rerankerModel(String v) { p.rerankerModel = v; return this; }

		public Builder maxContextTokens(int v) { p.maxContextTokens = v; return this; }

		public Builder promptTemplate(String v) { p.promptTemplate = v; return this; }

		public Builder hybridSearch(boolean v) { p.hybridSearch = v; return this; }

		public Builder fineTunedModelArn(String v) { p.fineTunedModelArn = v; return this; }

		public Builder createdBy(String v) { p.createdBy = v; return this; }

		public RagProfile build() {
			return p;
		}
	}
}

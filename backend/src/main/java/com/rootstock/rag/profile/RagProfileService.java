package com.rootstock.rag.profile;

import com.rootstock.rag.RagProperties;
import com.rootstock.rag.ingest.ChunkingStrategy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 1: resolves the tenant's active RAG profile, seeding a {@code default}
 * profile from {@code rootstock.rag.defaults} on first use. Versioning,
 * editing, activation and blue/green re-index land in phase 2.
 */
@Service
public class RagProfileService {

	private final RagProfileRepository repository;
	private final RagProperties properties;

	public RagProfileService(RagProfileRepository repository, RagProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	@Transactional
	public RagProfile activeProfile(String tenantId) {
		return repository.findByTenantIdAndActiveTrue(tenantId)
				.orElseGet(() -> seedDefault(tenantId));
	}

	private RagProfile seedDefault(String tenantId) {
		RagProperties.Defaults d = properties.defaults();
		RagProfile profile = new RagProfile.Builder()
				.tenantId(tenantId)
				.name("default")
				.versionNo(1)
				.active(true)
				.chunkingStrategy(ChunkingStrategy.valueOf(d.chunkingStrategy()))
				.chunkSize(d.chunkSize())
				.chunkOverlap(d.chunkOverlap())
				.embeddingModelId(d.embeddingModelId())
				.topK(d.topK())
				.similarityThreshold(d.similarityThreshold())
				.rerankerEnabled(false)
				.maxContextTokens(d.maxContextTokens())
				.promptTemplate(d.promptTemplate())
				.hybridSearch(false)
				.createdBy("system")
				.build();
		try {
			return repository.saveAndFlush(profile);
		}
		catch (DataIntegrityViolationException raced) {
			// Another request seeded it concurrently; the partial unique index rejected this one.
			return repository.findByTenantIdAndActiveTrue(tenantId).orElseThrow(() -> raced);
		}
	}
}

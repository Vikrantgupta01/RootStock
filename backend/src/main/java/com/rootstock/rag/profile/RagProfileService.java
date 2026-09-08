package com.rootstock.rag.profile;

import com.rootstock.common.DuplicateResourceException;
import com.rootstock.common.ResourceNotFoundException;
import com.rootstock.rag.RagProperties;
import com.rootstock.rag.document.ActiveVersionResolver;
import com.rootstock.rag.document.DocumentVersion;
import com.rootstock.rag.ingest.ChunkingStrategy;
import com.rootstock.rag.ingest.IngestionJob;
import com.rootstock.rag.ingest.IngestionJobKind;
import com.rootstock.rag.ingest.IngestionJobRepository;
import com.rootstock.rag.profile.dto.ActivationResponse;
import com.rootstock.rag.profile.dto.CreateRagProfileRequest;
import com.rootstock.rag.profile.dto.UpdateRagProfileRequest;
import com.rootstock.rag.tenant.TenantContext;
import com.rootstock.rag.vector.EmbeddingModelRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages a tenant's RAG profiles: seeding a {@code default}, creating named
 * profiles, versioning edits, and blue/green activation (see
 * {@link ProfileActivationMonitor}).
 */
@Service
public class RagProfileService {

	private final RagProfileRepository profiles;
	private final RagProfileActivationRepository activations;
	private final IngestionJobRepository jobs;
	private final ActiveVersionResolver activeVersions;
	private final EmbeddingModelRegistry embeddingModels;
	private final RagProperties properties;

	public RagProfileService(RagProfileRepository profiles, RagProfileActivationRepository activations,
			IngestionJobRepository jobs, ActiveVersionResolver activeVersions,
			EmbeddingModelRegistry embeddingModels, RagProperties properties) {
		this.profiles = profiles;
		this.activations = activations;
		this.jobs = jobs;
		this.activeVersions = activeVersions;
		this.embeddingModels = embeddingModels;
		this.properties = properties;
	}

	// ---- read -------------------------------------------------------------- -

	@Transactional
	public RagProfile activeProfile(String tenantId) {
		return profiles.findByTenantIdAndActiveTrue(tenantId).orElseGet(() -> seedDefault(tenantId));
	}

	/**
	 * One row per profile name: the active version if the name has one, otherwise
	 * its latest (draft) version. Seeds the {@code default} profile on first call.
	 */
	@Transactional
	public List<RagProfile> listProfiles() {
		String tenantId = TenantContext.require();
		Map<String, RagProfile> chosen = new LinkedHashMap<>();
		for (RagProfile p : profiles.findByTenantIdOrderByNameAscVersionNoDesc(tenantId)) {
			RagProfile current = chosen.get(p.getName());
			if (current == null || (!current.isActive() && p.isActive())) {
				chosen.put(p.getName(), p);
			}
		}
		if (chosen.isEmpty()) {
			RagProfile seeded = activeProfile(tenantId);
			chosen.put(seeded.getName(), seeded);
		}
		return new ArrayList<>(chosen.values());
	}

	@Transactional(readOnly = true)
	public RagProfile get(UUID id) {
		return requireProfile(id);
	}

	@Transactional(readOnly = true)
	public List<RagProfile> versionsOf(UUID id) {
		RagProfile profile = requireProfile(id);
		return profiles.findByTenantIdAndNameOrderByVersionNoDesc(profile.getTenantId(), profile.getName());
	}

	// ---- mutate ---------------------------------------------------------- -

	@Transactional
	public RagProfile create(CreateRagProfileRequest request) {
		String tenantId = TenantContext.require();
		if (profiles.findFirstByTenantIdAndNameOrderByVersionNoDesc(tenantId, request.name()).isPresent()) {
			throw new DuplicateResourceException("A profile named '" + request.name() + "' already exists.");
		}
		RagProperties.Defaults d = properties.defaults();
		String embeddingModelId = orElse(request.embeddingModelId(), d.embeddingModelId());
		requireKnownEmbeddingModel(embeddingModelId);

		RagProfile profile = new RagProfile.Builder()
				.tenantId(tenantId)
				.name(request.name().trim())
				.versionNo(1)
				.active(false)
				.chunkingStrategy(orElse(request.chunkingStrategy(), ChunkingStrategy.valueOf(d.chunkingStrategy())))
				.chunkSize(orElse(request.chunkSize(), d.chunkSize()))
				.chunkOverlap(orElse(request.chunkOverlap(), d.chunkOverlap()))
				.embeddingModelId(embeddingModelId)
				.chatModelId(request.chatModelId())
				.topK(orElse(request.topK(), d.topK()))
				.similarityThreshold(orElse(request.similarityThreshold(), d.similarityThreshold()))
				.rerankerEnabled(Boolean.TRUE.equals(request.rerankerEnabled()))
				.rerankerModel(request.rerankerModel())
				.maxContextTokens(orElse(request.maxContextTokens(), d.maxContextTokens()))
				.promptTemplate(orElse(request.promptTemplate(), d.promptTemplate()))
				.hybridSearch(Boolean.TRUE.equals(request.hybridSearch()))
				.createdBy(tenantId)
				.build();
		return profiles.save(profile);
	}

	@Transactional
	public RagProfile update(UUID id, UpdateRagProfileRequest request) {
		RagProfile source = requireProfile(id);
		String embeddingModelId = orElse(request.embeddingModelId(), source.getEmbeddingModelId());
		requireKnownEmbeddingModel(embeddingModelId);

		int nextVersion = profiles
				.findFirstByTenantIdAndNameOrderByVersionNoDesc(source.getTenantId(), source.getName())
				.map(RagProfile::getVersionNo).orElse(0) + 1;

		RagProfile next = new RagProfile.Builder()
				.tenantId(source.getTenantId())
				.name(source.getName())
				.versionNo(nextVersion)
				.active(false)
				.chunkingStrategy(orElse(request.chunkingStrategy(), source.getChunkingStrategy()))
				.chunkSize(orElse(request.chunkSize(), source.getChunkSize()))
				.chunkOverlap(orElse(request.chunkOverlap(), source.getChunkOverlap()))
				.embeddingModelId(embeddingModelId)
				.chatModelId(orElse(request.chatModelId(), source.getChatModelId()))
				.topK(orElse(request.topK(), source.getTopK()))
				.similarityThreshold(orElse(request.similarityThreshold(), source.getSimilarityThreshold()))
				.rerankerEnabled(orElse(request.rerankerEnabled(), source.isRerankerEnabled()))
				.rerankerModel(orElse(request.rerankerModel(), source.getRerankerModel()))
				.maxContextTokens(orElse(request.maxContextTokens(), source.getMaxContextTokens()))
				.promptTemplate(orElse(request.promptTemplate(), source.getPromptTemplate()))
				.hybridSearch(orElse(request.hybridSearch(), source.isHybridSearch()))
				.createdBy(source.getTenantId())
				.build();
		return profiles.save(next);
	}

	@Transactional
	public ActivationResponse activate(UUID id) {
		String tenantId = TenantContext.require();
		RagProfile target = requireProfile(id);
		RagProfile current = profiles.findByTenantIdAndActiveTrue(tenantId).orElse(null);

		if (current != null && current.getId().equals(target.getId())) {
			return ActivationResponse.immediate(target.getId(), current.getId());
		}
		activations.findByTenantIdAndState(tenantId, RagProfileActivation.State.PENDING).ifPresent(a -> {
			throw new DuplicateResourceException("A profile activation is already in progress for this tenant.");
		});

		if (!target.layoutDiffersFrom(current)) {
			// Query-time-only change: the existing chunks already match the new
			// layout, so flip the pointer immediately.
			switchActive(current, target);
			return ActivationResponse.immediate(target.getId(), current != null ? current.getId() : null);
		}

		List<DocumentVersion> toReindex = activeVersions.activeIndexed(tenantId);
		for (DocumentVersion v : toReindex) {
			jobs.save(new IngestionJob(tenantId, IngestionJobKind.REINDEX, v.getId(), target.getId(),
					properties.ingest().maxAttempts()));
		}
		RagProfileActivation activation = activations.save(new RagProfileActivation(
				tenantId, target.getId(), current != null ? current.getId() : null, toReindex.size()));

		if (toReindex.isEmpty()) {
			// Nothing indexed yet -- switch right away.
			switchActive(current, target);
			activation.setState(RagProfileActivation.State.COMPLETED);
			activation.setCompletedAt(java.time.Instant.now());
		}
		return ActivationResponse.of(activation);
	}

	/**
	 * Deactivates {@code from} and activates {@code to}, flushing between the two
	 * writes so the "one active profile per tenant" partial unique index is never
	 * momentarily violated.
	 */
	private void switchActive(RagProfile from, RagProfile to) {
		if (from != null && from.isActive()) {
			from.setActive(false);
			profiles.saveAndFlush(from);
		}
		if (to != null) {
			to.setActive(true);
			profiles.saveAndFlush(to);
		}
	}

	/**
	 * Completes any blue/green activation whose re-index jobs have all finished:
	 * flips the active pointer on success (and queues a cleanup of the old
	 * layout's chunks), or marks the activation failed. Driven by
	 * {@link ProfileActivationMonitor}.
	 */
	@Transactional
	public void finalizePendingActivations() {
		var terminalStates = List.of(
				com.rootstock.rag.ingest.IngestionJobState.SUCCEEDED,
				com.rootstock.rag.ingest.IngestionJobState.FAILED);
		var failedState = List.of(com.rootstock.rag.ingest.IngestionJobState.FAILED);

		for (RagProfileActivation a : activations.findByState(RagProfileActivation.State.PENDING)) {
			long terminal = jobs.countByProfileIdAndKindAndStateIn(
					a.getTargetProfileId(), IngestionJobKind.REINDEX, terminalStates);
			if (terminal < a.getTotalJobs()) {
				continue;
			}
			long failed = jobs.countByProfileIdAndKindAndStateIn(
					a.getTargetProfileId(), IngestionJobKind.REINDEX, failedState);
			if (failed > 0) {
				a.setState(RagProfileActivation.State.FAILED);
				a.setErrorMessage(failed + " of " + a.getTotalJobs() + " re-index jobs failed");
				a.setCompletedAt(java.time.Instant.now());
				continue;
			}
			RagProfile previous = a.getPreviousProfileId() != null
					? profiles.findById(a.getPreviousProfileId()).orElse(null) : null;
			RagProfile targetProfile = profiles.findById(a.getTargetProfileId()).orElse(null);
			switchActive(previous, targetProfile);
			a.setState(RagProfileActivation.State.COMPLETED);
			a.setCompletedAt(java.time.Instant.now());
			if (a.getPreviousProfileId() != null) {
				jobs.save(new IngestionJob(a.getTenantId(), IngestionJobKind.CLEANUP, null,
						a.getPreviousProfileId(), properties.ingest().maxAttempts()));
			}
		}
	}

	@Transactional(readOnly = true)
	public ActivationResponse activationStatus(UUID activationId) {
		String tenantId = TenantContext.require();
		RagProfileActivation a = activations.findById(activationId)
				.filter(x -> x.getTenantId().equals(tenantId))
				.orElseThrow(() -> ResourceNotFoundException.of("Profile activation", activationId));
		return ActivationResponse.of(a);
	}

	// ---- helpers ---------------------------------------------------------- -

	private RagProfile requireProfile(UUID id) {
		return profiles.findByTenantIdAndId(TenantContext.require(), id)
				.orElseThrow(() -> ResourceNotFoundException.of("RAG profile", id));
	}

	private void requireKnownEmbeddingModel(String id) {
		if (!embeddingModels.availableIds().contains(id)) {
			throw new IllegalArgumentException("Unknown or disabled embedding model '" + id
					+ "'. Available: " + embeddingModels.availableIds());
		}
	}

	private RagProfile seedDefault(String tenantId) {
		RagProperties.Defaults d = properties.defaults();
		RagProfile profile = new RagProfile.Builder()
				.tenantId(tenantId).name("default").versionNo(1).active(true)
				.chunkingStrategy(ChunkingStrategy.valueOf(d.chunkingStrategy()))
				.chunkSize(d.chunkSize()).chunkOverlap(d.chunkOverlap())
				.embeddingModelId(d.embeddingModelId())
				.topK(d.topK()).similarityThreshold(d.similarityThreshold())
				.rerankerEnabled(false).maxContextTokens(d.maxContextTokens())
				.promptTemplate(d.promptTemplate()).hybridSearch(false).createdBy("system")
				.build();
		try {
			return profiles.saveAndFlush(profile);
		}
		catch (DataIntegrityViolationException raced) {
			return profiles.findByTenantIdAndActiveTrue(tenantId).orElseThrow(() -> raced);
		}
	}

	private static <T> T orElse(T value, T fallback) {
		return value != null ? value : fallback;
	}

	private static int orElse(Integer value, int fallback) {
		return value != null ? value : fallback;
	}

	private static double orElse(Double value, double fallback) {
		return value != null ? value : fallback;
	}

	private static boolean orElse(Boolean value, boolean fallback) {
		return value != null ? value : fallback;
	}
}

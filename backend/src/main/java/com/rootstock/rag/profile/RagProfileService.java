package com.rootstock.rag.profile;

import com.rootstock.common.DuplicateResourceException;
import com.rootstock.common.ResourceNotFoundException;
import com.rootstock.rag.RagProperties;
import com.rootstock.rag.profile.dto.CreateRagProfileRequest;
import com.rootstock.rag.profile.dto.UpdateRagProfileRequest;
import com.rootstock.rag.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages a tenant's RAG profiles: seeding a {@code default}, creating named
 * profiles, versioning edits, and activation. Profiles hold only query-time
 * knobs (top-k, similarity threshold, chat model, prompt template, reranker) --
 * ingestion, chunking, and embedding are owned by the Bedrock Knowledge Base, so
 * switching the active profile is always an immediate pointer flip.
 */
@Service
public class RagProfileService {

	private final RagProfileRepository profiles;
	private final RagProperties properties;

	public RagProfileService(RagProfileRepository profiles, RagProperties properties) {
		this.profiles = profiles;
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
	@PreAuthorize("hasRole('ADMIN')")
	public RagProfile create(CreateRagProfileRequest request) {
		String tenantId = TenantContext.require();
		if (profiles.findFirstByTenantIdAndNameOrderByVersionNoDesc(tenantId, request.name()).isPresent()) {
			throw new DuplicateResourceException("A profile named '" + request.name() + "' already exists.");
		}
		RagProperties.Defaults d = properties.defaults();
		RagProfile profile = new RagProfile.Builder()
				.tenantId(tenantId)
				.name(request.name().trim())
				.versionNo(1)
				.active(false)
				.chatModelId(request.chatModelId())
				.topK(orElse(request.topK(), d.topK()))
				.similarityThreshold(orElse(request.similarityThreshold(), d.similarityThreshold()))
				.rerankerEnabled(Boolean.TRUE.equals(request.rerankerEnabled()))
				.rerankerModel(request.rerankerModel())
				.maxContextTokens(orElse(request.maxContextTokens(), d.maxContextTokens()))
				.promptTemplate(orElse(request.promptTemplate(), d.promptTemplate()))
				.createdBy(tenantId)
				.build();
		return profiles.save(profile);
	}

	@Transactional
	@PreAuthorize("hasRole('ADMIN')")
	public RagProfile update(UUID id, UpdateRagProfileRequest request) {
		RagProfile source = requireProfile(id);
		int nextVersion = profiles
				.findFirstByTenantIdAndNameOrderByVersionNoDesc(source.getTenantId(), source.getName())
				.map(RagProfile::getVersionNo).orElse(0) + 1;

		RagProfile next = new RagProfile.Builder()
				.tenantId(source.getTenantId())
				.name(source.getName())
				.versionNo(nextVersion)
				.active(false)
				.chatModelId(orElse(request.chatModelId(), source.getChatModelId()))
				.topK(orElse(request.topK(), source.getTopK()))
				.similarityThreshold(orElse(request.similarityThreshold(), source.getSimilarityThreshold()))
				.rerankerEnabled(orElse(request.rerankerEnabled(), source.isRerankerEnabled()))
				.rerankerModel(orElse(request.rerankerModel(), source.getRerankerModel()))
				.maxContextTokens(orElse(request.maxContextTokens(), source.getMaxContextTokens()))
				.promptTemplate(orElse(request.promptTemplate(), source.getPromptTemplate()))
				.createdBy(source.getTenantId())
				.build();
		return profiles.save(next);
	}

	/** Flips the active pointer to {@code id}. Always immediate -- no re-index to wait for. */
	@Transactional
	@PreAuthorize("hasRole('ADMIN')")
	public RagProfile activate(UUID id) {
		String tenantId = TenantContext.require();
		RagProfile target = requireProfile(id);
		RagProfile current = profiles.findByTenantIdAndActiveTrue(tenantId).orElse(null);
		if (current == null || !current.getId().equals(target.getId())) {
			switchActive(current, target);
		}
		return target;
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

	// ---- helpers ---------------------------------------------------------- -

	private RagProfile requireProfile(UUID id) {
		return profiles.findByTenantIdAndId(TenantContext.require(), id)
				.orElseThrow(() -> ResourceNotFoundException.of("RAG profile", id));
	}

	private RagProfile seedDefault(String tenantId) {
		RagProperties.Defaults d = properties.defaults();
		RagProfile profile = new RagProfile.Builder()
				.tenantId(tenantId).name("default").versionNo(1).active(true)
				.topK(d.topK()).similarityThreshold(d.similarityThreshold())
				.rerankerEnabled(false).maxContextTokens(d.maxContextTokens())
				.promptTemplate(d.promptTemplate()).createdBy("system")
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

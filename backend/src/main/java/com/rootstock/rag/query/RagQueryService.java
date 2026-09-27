package com.rootstock.rag.query;

import com.rootstock.chat.ChatService;
import com.rootstock.rag.RagProperties;
import com.rootstock.rag.document.ActiveVersionResolver;
import com.rootstock.rag.document.Document;
import com.rootstock.rag.document.DocumentRepository;
import com.rootstock.rag.document.DocumentVersion;
import com.rootstock.rag.document.DocumentVersionRepository;
import com.rootstock.rag.profile.RagProfile;
import com.rootstock.rag.profile.RagProfileService;
import com.rootstock.rag.query.dto.Citation;
import com.rootstock.rag.query.dto.RagQueryRequest;
import com.rootstock.rag.query.dto.RagQueryResponse;
import com.rootstock.rag.tenant.TenantContext;
import com.rootstock.rag.vector.BedrockKnowledgeBaseClient;
import com.rootstock.rag.vector.KnowledgeBaseFilters;
import com.rootstock.rag.vector.RagChunkMetadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;

/**
 * Retrieval-augmented answering: pick the profile, retrieve the tenant's active
 * chunks from the Bedrock Knowledge Base, ground a prompt in them, and return the
 * answer with citations.
 */
@Service
public class RagQueryService {

	private static final String SYSTEM_PROMPT =
			"You are a precise retrieval-augmented assistant. Answer only from the provided context. "
					+ "Cite supporting passages inline as [n]. If the context does not contain the answer, say so.";
	private static final int SNIPPET_CHARS = 240;

	private final RagProfileService profiles;
	private final ActiveVersionResolver activeVersions;
	private final BedrockKnowledgeBaseClient kb;
	private final RagProperties properties;
	private final ChatService chatService;
	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;

	public RagQueryService(RagProfileService profiles, ActiveVersionResolver activeVersions,
			BedrockKnowledgeBaseClient kb, RagProperties properties, ChatService chatService,
			DocumentRepository documents, DocumentVersionRepository versions) {
		this.profiles = profiles;
		this.activeVersions = activeVersions;
		this.kb = kb;
		this.properties = properties;
		this.chatService = chatService;
		this.documents = documents;
		this.versions = versions;
	}

	public RagQueryResponse query(RagQueryRequest request) {
		String tenantId = TenantContext.require();
		RagProfile profile = request.profileId() != null
				? profiles.get(request.profileId())
				: profiles.activeProfile(tenantId);

		List<DocumentVersion> active = activeVersions.activeIndexed(tenantId);
		List<UUID> usedVersionIds = active.stream().map(DocumentVersion::getId).toList();
		if (active.isEmpty()) {
			return ungrounded(profile, usedVersionIds,
					"There are no indexed documents in this knowledge base yet.");
		}

		int topK = request.topK() != null ? request.topK() : profile.getTopK();
		double threshold = request.similarityThreshold() != null
				? request.similarityThreshold() : profile.getSimilarityThreshold();

		RetrievalFilter filter = KnowledgeBaseFilters.retrieval(tenantId,
				usedVersionIds.stream().map(UUID::toString).toList());
		String rerankerModelArn = rerankerModelArn(profile);
		List<KnowledgeBaseRetrievalResult> hits = kb.retrieve(
				properties.bedrock().knowledgeBaseId(), request.question(), topK, filter, rerankerModelArn).stream()
				.filter(h -> h.score() == null || h.score() >= threshold)
				.toList();
		if (hits.isEmpty()) {
			return ungrounded(profile, usedVersionIds,
					"I couldn't find anything relevant to that question in the knowledge base.");
		}

		List<Citation> citations = toCitations(tenantId, hits);
		String prompt = profile.getPromptTemplate()
				.replace("{context}", renderContext(hits, citations))
				.replace("{question}", request.question());
		String answer = chatService.generate(SYSTEM_PROMPT, prompt);

		return new RagQueryResponse(answer, true, citations, profile.getId(), profile.getName(),
				profile.getVersionNo(), usedVersionIds);
	}

	private String rerankerModelArn(RagProfile profile) {
		if (!profile.isRerankerEnabled() || profile.getRerankerModel() == null) {
			return null;
		}
		return "arn:aws:bedrock:" + properties.bedrock().region() + "::foundation-model/" + profile.getRerankerModel();
	}

	private RagQueryResponse ungrounded(RagProfile profile, List<UUID> usedVersionIds, String answer) {
		return new RagQueryResponse(answer, false, List.of(), profile.getId(), profile.getName(),
				profile.getVersionNo(), usedVersionIds);
	}

	private String renderContext(List<KnowledgeBaseRetrievalResult> hits, List<Citation> citations) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hits.size(); i++) {
			Citation c = citations.get(i);
			sb.append('[').append(i + 1).append("] source: ").append(c.sourceKey());
			if (c.versionNo() != null) {
				sb.append(" (v").append(c.versionNo()).append(')');
			}
			sb.append('\n').append(text(hits.get(i))).append("\n\n");
		}
		return sb.toString();
	}

	private List<Citation> toCitations(String tenantId, List<KnowledgeBaseRetrievalResult> hits) {
		List<UUID> documentIds = hits.stream()
				.map(h -> uuid(metadataString(h, RagChunkMetadata.DOCUMENT_ID)))
				.filter(java.util.Objects::nonNull).distinct().toList();
		List<UUID> versionIds = hits.stream()
				.map(h -> uuid(metadataString(h, RagChunkMetadata.DOCUMENT_VERSION_ID)))
				.filter(java.util.Objects::nonNull).distinct().toList();

		Map<UUID, Document> docs = documentIds.isEmpty() ? Map.of()
				: documents.findByTenantIdAndIdIn(tenantId, documentIds).stream()
						.collect(Collectors.toMap(Document::getId, Function.identity()));
		Map<UUID, DocumentVersion> vers = versionIds.isEmpty() ? Map.of()
				: versions.findByTenantIdAndIdIn(tenantId, versionIds).stream()
						.collect(Collectors.toMap(DocumentVersion::getId, Function.identity()));

		Map<Integer, Citation> byRank = new LinkedHashMap<>();
		for (int i = 0; i < hits.size(); i++) {
			var h = hits.get(i);
			UUID docId = uuid(metadataString(h, RagChunkMetadata.DOCUMENT_ID));
			UUID verId = uuid(metadataString(h, RagChunkMetadata.DOCUMENT_VERSION_ID));
			Document doc = docId != null ? docs.get(docId) : null;
			DocumentVersion ver = verId != null ? vers.get(verId) : null;
			byRank.put(i + 1, new Citation(
					i + 1,
					docId,
					doc != null ? doc.getSourceKey() : null,
					doc != null ? doc.getDisplayName() : null,
					ver != null ? ver.getVersionNo() : null,
					null,
					h.score(),
					snippet(text(h))));
		}
		return List.copyOf(byRank.values());
	}

	private static String text(KnowledgeBaseRetrievalResult hit) {
		return hit.content() != null ? hit.content().text() : null;
	}

	private static String metadataString(KnowledgeBaseRetrievalResult hit, String key) {
		var value = hit.metadata() != null ? hit.metadata().get(key) : null;
		return value != null && value.isString() ? value.asString() : null;
	}

	private static String snippet(String text) {
		if (text == null) {
			return null;
		}
		String flat = text.strip().replaceAll("\\s+", " ");
		return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
	}

	private static UUID uuid(Object value) {
		try {
			return value == null ? null : UUID.fromString(String.valueOf(value));
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}
}

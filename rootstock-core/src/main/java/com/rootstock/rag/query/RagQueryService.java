package com.rootstock.rag.query;

import com.rootstock.auth.AuthContext;
import com.rootstock.chat.ChatService;
import com.rootstock.conversation.ChatMessage;
import com.rootstock.conversation.Conversation;
import com.rootstock.conversation.ConversationKind;
import com.rootstock.conversation.ConversationService;
import com.rootstock.conversation.MessageRole;
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
	/**
	 * Retrieval is a similarity search over chunks, so it only ever sees the text
	 * it is given -- "and what about its price?" matches nothing, because the
	 * thing being priced is in an earlier turn, not in the query. This rewrites
	 * such a follow-up into a query that stands on its own before anything is
	 * retrieved.
	 */
	private static final String CONDENSE_SYSTEM =
			"You rewrite a follow-up message into one standalone question for a document search. "
					+ "Resolve every pronoun and implicit reference using the conversation. "
					+ "Keep names, numbers, product terms and the original wording exactly as written. "
					+ "Reply with the rewritten question and nothing else -- no preamble, no quotes, no "
					+ "explanation. If the message already stands on its own, reply with it unchanged.";

	private static final int SNIPPET_CHARS = 240;

	private final RagProfileService profiles;
	private final ActiveVersionResolver activeVersions;
	private final BedrockKnowledgeBaseClient kb;
	private final RagProperties properties;
	private final ChatService chatService;
	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;
	private final ConversationService conversations;

	public RagQueryService(RagProfileService profiles, ActiveVersionResolver activeVersions,
			BedrockKnowledgeBaseClient kb, RagProperties properties, ChatService chatService,
			DocumentRepository documents, DocumentVersionRepository versions,
			ConversationService conversations) {
		this.profiles = profiles;
		this.activeVersions = activeVersions;
		this.kb = kb;
		this.properties = properties;
		this.chatService = chatService;
		this.documents = documents;
		this.versions = versions;
		this.conversations = conversations;
	}

	public RagQueryResponse query(RagQueryRequest request) {
		String tenantId = TenantContext.require();
		RagProfile profile = request.profileId() != null
				? profiles.get(request.profileId())
				: profiles.activeProfile(tenantId);

		Conversation conversation = conversations.resolve(
				request.conversationId(), ConversationKind.RAG, request.question());
		List<ChatMessage> history = conversations.history(conversation.getId());

		List<DocumentVersion> active = activeVersions.activeIndexed(tenantId);
		List<UUID> usedVersionIds = active.stream().map(DocumentVersion::getId).toList();
		if (active.isEmpty()) {
			return finish(conversation, request.question(), ungrounded(profile, conversation, request.question(),
					usedVersionIds, "There are no indexed documents in this knowledge base yet."));
		}

		// Rewritten before retrieval, not after: the filter and the similarity
		// search both run against this text, so a follow-up that still says "it"
		// would search for the wrong thing.
		String retrievalQuery = condense(history, request.question());

		int topK = request.topK() != null ? request.topK() : profile.getTopK();
		double threshold = request.similarityThreshold() != null
				? request.similarityThreshold() : profile.getSimilarityThreshold();

		// Bedrock applies this filter itself, so a chunk the caller's groups don't
		// cover is never returned at all -- the chat model never sees it, and there
		// is nothing to leak through the answer. Null for an admin: no group
		// restriction, matching what every user saw before ACLs existed.
		RetrievalFilter filter = KnowledgeBaseFilters.retrieval(tenantId,
				usedVersionIds.stream().map(UUID::toString).toList(),
				AuthContext.retrievalGroupsOrNull());
		String rerankerModelArn = rerankerModelArn(profile);
		List<KnowledgeBaseRetrievalResult> rawHits = kb.retrieve(
				properties.bedrock().knowledgeBaseId(), retrievalQuery, topK, filter, rerankerModelArn);
		// similarityThreshold is calibrated for raw cosine similarity. Once a
		// reranker scores these, "score" means a relevance score on Cohere's own
		// scale instead -- routinely well under 0.5 for a genuinely correct match
		// -- so applying the same cutoff would silently reject good answers. The
		// reranker has already done the relevance judgment; trust its ordering
		// (and topK) instead of re-filtering on a number that no longer means the
		// same thing.
		List<KnowledgeBaseRetrievalResult> hits = rerankerModelArn != null
				? rawHits
				: rawHits.stream().filter(h -> h.score() == null || h.score() >= threshold).toList();
		if (hits.isEmpty()) {
			return finish(conversation, request.question(), ungrounded(profile, conversation, retrievalQuery,
					usedVersionIds, "I couldn't find anything relevant to that question in the knowledge base."));
		}

		List<Citation> citations = toCitations(tenantId, hits);
		// The template still gets the question as asked, not the rewrite: the
		// rewrite exists to retrieve well, while the answer should address what
		// the person actually typed. The earlier turns come along so the reply
		// reads as part of the conversation.
		String prompt = profile.getPromptTemplate()
				.replace("{context}", renderContext(hits, citations))
				.replace("{question}", request.question());
		String answer = chatService.generate(
				SYSTEM_PROMPT, ConversationService.toPromptMessages(history), prompt);

		return finish(conversation, request.question(),
				new RagQueryResponse(answer, true, citations, conversation.getId(), retrievalQuery,
						profile.getId(), profile.getName(), profile.getVersionNo(), usedVersionIds));
	}

	/**
	 * Records the exchange before handing the answer back, including the ones
	 * that found nothing -- "I couldn't find that" is real context for whatever
	 * the person asks next, and a thread with holes in it condenses badly.
	 */
	private RagQueryResponse finish(Conversation conversation, String question, RagQueryResponse response) {
		conversations.append(conversation, question, response.answer());
		return response;
	}

	/** @return a standalone version of {@code question}, or it unchanged when the thread is new. */
	private String condense(List<ChatMessage> history, String question) {
		if (history.isEmpty()) {
			return question;
		}
		StringBuilder transcript = new StringBuilder();
		for (ChatMessage message : history) {
			transcript.append(message.getRole() == MessageRole.USER ? "User: " : "Assistant: ")
					.append(message.getContent()).append('\n');
		}
		String rewritten = chatService.generate(CONDENSE_SYSTEM,
				"Conversation so far:\n" + transcript + "\nFollow-up message: " + question);
		// A rewrite that came back empty, or that ran away into prose, is worse
		// than no rewrite at all -- fall back to what was actually asked.
		if (rewritten == null || rewritten.isBlank() || rewritten.length() > question.length() * 4 + 200) {
			return question;
		}
		return rewritten.strip();
	}

	private String rerankerModelArn(RagProfile profile) {
		if (!profile.isRerankerEnabled() || profile.getRerankerModel() == null) {
			return null;
		}
		return "arn:aws:bedrock:" + properties.bedrock().region() + "::foundation-model/" + profile.getRerankerModel();
	}

	private RagQueryResponse ungrounded(RagProfile profile, Conversation conversation, String retrievalQuery,
			List<UUID> usedVersionIds, String answer) {
		return new RagQueryResponse(answer, false, List.of(), conversation.getId(), retrievalQuery,
				profile.getId(), profile.getName(), profile.getVersionNo(), usedVersionIds);
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

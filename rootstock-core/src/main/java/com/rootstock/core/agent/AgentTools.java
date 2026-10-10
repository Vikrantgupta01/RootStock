package com.rootstock.core.agent;

import com.rootstock.core.rag.RagProperties;
import com.rootstock.core.rag.document.ActiveVersionResolver;
import com.rootstock.core.rag.document.Document;
import com.rootstock.core.rag.document.DocumentRepository;
import com.rootstock.core.rag.document.DocumentVersion;
import com.rootstock.core.rag.vector.BedrockKnowledgeBaseClient;
import com.rootstock.core.rag.vector.KnowledgeBaseFilters;
import com.rootstock.core.rag.vector.RagChunkMetadata;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;

/**
 * What the agent can act with. Each {@link Tool} method becomes a tool
 * definition the model sees; its description is the model's only documentation,
 * so it says when to use the tool, not just what it does.
 *
 * <p>Tenant and access groups arrive through {@link ToolContext} rather than
 * being read from {@code TenantContext}/{@code AuthContext} here: they are
 * captured once on the request thread by {@link AgentService}, so a tool never
 * depends on which thread the graph happens to run it on. The context is never
 * sent to the model.
 *
 * <p>Failures come back as text, not exceptions -- "nothing found" is an
 * observation the agent can reason about and retry around.
 */
@Component
public class AgentTools {

	static final String TENANT_ID = "tenantId";
	/** Absent for an admin: no group restriction, as in {@code AuthContext.retrievalGroupsOrNull}. */
	static final String RETRIEVAL_GROUPS = "retrievalGroups";

	private final ActiveVersionResolver activeVersions;
	private final BedrockKnowledgeBaseClient kb;
	private final DocumentRepository documents;
	private final RagProperties properties;

	public AgentTools(ActiveVersionResolver activeVersions, BedrockKnowledgeBaseClient kb,
			DocumentRepository documents, RagProperties properties) {
		this.activeVersions = activeVersions;
		this.kb = kb;
		this.documents = documents;
		this.properties = properties;
	}

	@Tool(name = "currentDateTime",
			description = "Returns the current date, time and weekday in UTC (ISO-8601). Use it for any "
					+ "question that depends on today's date, such as deadlines, ages or 'how long ago'.",
			resultConverter = PlainTextResultConverter.class)
	public String currentDateTime() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		return now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " (" + now.getDayOfWeek() + ")";
	}

	@Tool(name = "searchKnowledgeBase",
			description = "Searches the organisation's document knowledge base and returns the most relevant "
					+ "passages, numbered [n], each with its source document. Use it for anything about the "
					+ "organisation's own policies, products, processes or data. One topic per call; call it "
					+ "again with different wording if the passages don't answer the question.",
			resultConverter = PlainTextResultConverter.class)
	public String searchKnowledgeBase(
			@ToolParam(description = "A focused, standalone search query: name the subject explicitly "
					+ "rather than referring back to the conversation") String query,
			ToolContext toolContext) {
		Map<String, Object> context = toolContext.getContext();
		String tenantId = (String) context.get(TENANT_ID);
		@SuppressWarnings("unchecked")
		List<String> groups = (List<String>) context.get(RETRIEVAL_GROUPS);

		List<UUID> activeVersionIds = activeVersions.activeIndexed(tenantId).stream()
				.map(DocumentVersion::getId).toList();
		if (activeVersionIds.isEmpty()) {
			return "The knowledge base has no indexed documents yet.";
		}
		// Same filter RagQueryService applies: Bedrock drops anything outside the
		// tenant's active versions and the caller's groups before returning hits,
		// so the agent can't reason over -- or leak -- what the caller can't see.
		RetrievalFilter filter = KnowledgeBaseFilters.retrieval(tenantId,
				activeVersionIds.stream().map(UUID::toString).toList(), groups);
		RagProperties.Defaults defaults = properties.defaults();
		List<KnowledgeBaseRetrievalResult> hits = kb.retrieve(properties.bedrock().knowledgeBaseId(),
				query, defaults.topK(), filter, null).stream()
				.filter(h -> h.score() == null || h.score() >= defaults.similarityThreshold())
				.toList();
		if (hits.isEmpty()) {
			return "No passages matched \"" + query + "\".";
		}
		return render(tenantId, hits);
	}

	private String render(String tenantId, List<KnowledgeBaseRetrievalResult> hits) {
		List<UUID> documentIds = hits.stream()
				.map(h -> uuid(metadataString(h, RagChunkMetadata.DOCUMENT_ID)))
				.filter(Objects::nonNull).distinct().toList();
		Map<UUID, Document> docs = documentIds.isEmpty() ? Map.of()
				: documents.findByTenantIdAndIdIn(tenantId, documentIds).stream()
						.collect(Collectors.toMap(Document::getId, Function.identity()));

		StringBuilder out = new StringBuilder();
		for (int i = 0; i < hits.size(); i++) {
			KnowledgeBaseRetrievalResult hit = hits.get(i);
			Document doc = docs.get(uuid(metadataString(hit, RagChunkMetadata.DOCUMENT_ID)));
			out.append('[').append(i + 1).append("] source: ")
					.append(doc != null ? doc.getDisplayName() : "unknown document").append('\n')
					.append(hit.content() != null ? hit.content().text() : "").append("\n\n");
		}
		return out.toString().strip();
	}

	private static String metadataString(KnowledgeBaseRetrievalResult hit, String key) {
		var value = hit.metadata() != null ? hit.metadata().get(key) : null;
		return value != null && value.isString() ? value.asString() : null;
	}

	private static UUID uuid(String value) {
		try {
			return value == null ? null : UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}

package com.rootstock.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.rootstock.core.rag.RagProperties;
import com.rootstock.core.rag.document.ActiveVersionResolver;
import com.rootstock.core.rag.document.Document;
import com.rootstock.core.rag.document.DocumentRepository;
import com.rootstock.core.rag.document.DocumentVersion;
import com.rootstock.core.rag.vector.BedrockKnowledgeBaseClient;
import com.rootstock.core.rag.vector.RagChunkMetadata;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;

class AgentToolsTest {

	private final ActiveVersionResolver activeVersions = mock(ActiveVersionResolver.class);
	private final BedrockKnowledgeBaseClient kb = mock(BedrockKnowledgeBaseClient.class);
	private final DocumentRepository documents = mock(DocumentRepository.class);
	private final AgentTools tools = new AgentTools(activeVersions, kb, documents, new RagProperties(null,
			new RagProperties.Bedrock("kb-1", "ds-1", "us-east-1"), null,
			new RagProperties.Defaults(4, 0.5, 4000, "")));

	private final ToolContext context = new ToolContext(Map.of(AgentTools.TENANT_ID, "t1"));

	@Test
	void reportsAnEmptyKnowledgeBaseWithoutSearching() {
		given(activeVersions.activeIndexed("t1")).willReturn(List.of());

		assertThat(tools.searchKnowledgeBase("refund policy", context))
				.isEqualTo("The knowledge base has no indexed documents yet.");
		verifyNoInteractions(kb);
	}

	@Test
	void numbersPassagesWithTheirSourceAndDropsWeakHits() {
		DocumentVersion version = mock(DocumentVersion.class);
		given(version.getId()).willReturn(UUID.randomUUID());
		given(activeVersions.activeIndexed("t1")).willReturn(List.of(version));

		UUID documentId = UUID.randomUUID();
		Document document = mock(Document.class);
		given(document.getId()).willReturn(documentId);
		given(document.getDisplayName()).willReturn("Refund Policy.pdf");
		given(documents.findByTenantIdAndIdIn(eq("t1"), any())).willReturn(List.of(document));

		given(kb.retrieve(eq("kb-1"), eq("refund policy"), anyInt(), any(), any())).willReturn(List.of(
				hit("Refunds within 30 days of delivery.", 0.9, documentId),
				hit("Unrelated passage.", 0.1, documentId)));

		assertThat(tools.searchKnowledgeBase("refund policy", context))
				.isEqualTo("[1] source: Refund Policy.pdf\nRefunds within 30 days of delivery.");
	}

	private static KnowledgeBaseRetrievalResult hit(String text, double score, UUID documentId) {
		return KnowledgeBaseRetrievalResult.builder()
				.content(c -> c.text(text))
				.score(score)
				.metadata(Map.of(RagChunkMetadata.DOCUMENT_ID,
						software.amazon.awssdk.core.document.Document.fromString(documentId.toString())))
				.build();
	}
}

package com.rootstock.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.TestcontainersConfiguration;
import com.rootstock.auth.TestTokens;
import com.rootstock.rag.ingest.IngestionService;
import com.rootstock.rag.vector.BedrockKnowledgeBaseClient;
import com.rootstock.rag.vector.RagChunkMetadata;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJobStatistics;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;

/**
 * End-to-end retrieval: upload -> ingest -> POST /api/rag/query returns a
 * grounded answer with citations pointing at the uploaded document.
 * {@link BedrockKnowledgeBaseClient#retrieve} is stubbed to return a hit tagged
 * with the uploaded document's own metadata (there is no local/Testcontainers
 * stand-in for a real Knowledge Base), and a stub {@link ChatModel} stands in for
 * Bedrock's chat model, so no AWS is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RagQueryIntegrationTest.StubChatModelConfig.class})
class RagQueryIntegrationTest {

	static final String STUB_ANSWER = "Refunds are available within 14 days of purchase [1].";

	@TestConfiguration(proxyBeanMethods = false)
	static class StubChatModelConfig {
		@Bean
		ChatModel stubChatModel() {
			return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(STUB_ANSWER))));
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	IngestionService ingestionService;

	@MockitoBean
	BedrockKnowledgeBaseClient kb;

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping RAG query integration test");
	}

	@org.junit.jupiter.api.BeforeEach
	void stubSuccessfulSync() {
		given(kb.sync(any(), any())).willReturn(IngestionJobStatistics.builder()
				.numberOfDocumentsScanned(1L).numberOfDocumentsFailed(0L).build());
	}

	private void drainIngestionQueue() {
		for (UUID jobId : ingestionService.claim(10)) {
			ingestionService.process(jobId);
		}
	}

	@Test
	void queryReturnsGroundedAnswerWithCitations() throws Exception {
		String tenant = "q-" + UUID.randomUUID().toString().substring(0, 8);
		byte[] body = "The RootStock refund policy allows refunds within 14 days of purchase.".getBytes();

		String uploadJson = mockMvc.perform(multipart("/api/rag/documents")
						.file(new MockMultipartFile("file", "policy.txt", "text/plain", body))
						.with(TestTokens.admin(tenant)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID documentId = UUID.fromString(JsonPath.read(uploadJson, "$.documentId"));
		UUID versionId = UUID.fromString(JsonPath.read(uploadJson, "$.version.id"));
		drainIngestionQueue();

		KnowledgeBaseRetrievalResult hit = KnowledgeBaseRetrievalResult.builder()
				.content(c -> c.text(new String(body)))
				.score(0.9)
				.metadata(Map.of(
						RagChunkMetadata.TENANT_ID, Document.fromString(tenant),
						RagChunkMetadata.DOCUMENT_ID, Document.fromString(documentId.toString()),
						RagChunkMetadata.DOCUMENT_VERSION_ID, Document.fromString(versionId.toString())))
				.build();
		given(kb.retrieve(eq("test-kb"), any(), anyInt(), any(), any())).willReturn(List.of(hit));

		String json = mockMvc.perform(post("/api/rag/query")
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"How long do I have to request a refund?\",\"similarityThreshold\":0.0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.grounded").value(true))
				.andExpect(jsonPath("$.answer").value(STUB_ANSWER))
				.andExpect(jsonPath("$.citations[0].sourceKey").value("policy.txt"))
				.andReturn().getResponse().getContentAsString();

		List<Object> citations = JsonPath.read(json, "$.citations");
		assertThat(citations).isNotEmpty();
		assertThat((Integer) JsonPath.read(json, "$.citations[0].versionNo")).isEqualTo(1);
	}

	@Test
	void queryWithoutDocumentsIsNotGrounded() throws Exception {
		mockMvc.perform(post("/api/rag/query")
						.with(TestTokens.admin("empty-" + UUID.randomUUID()))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"anything?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.grounded").value(false))
				.andExpect(jsonPath("$.citations.length()").value(0));
	}
}

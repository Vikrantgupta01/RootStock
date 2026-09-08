package com.rootstock.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.TestcontainersConfiguration;
import com.rootstock.rag.ingest.IngestionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

/**
 * End-to-end retrieval: upload -> ingest (fake embeddings) -> POST /api/rag/query
 * returns a grounded answer with citations pointing at the uploaded document.
 * A stub {@link ChatModel} stands in for Bedrock so no AWS is needed.
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

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping RAG query integration test");
	}

	private void drainIngestionQueue() {
		for (UUID jobId : ingestionService.claim(10)) {
			ingestionService.process(jobId);
		}
	}

	@Test
	void queryReturnsGroundedAnswerWithCitations() throws Exception {
		String tenant = "q-" + UUID.randomUUID().toString().substring(0, 8);
		byte[] body = ("""
				The RootStock refund policy allows refunds within 14 days of purchase.
				Refunds are processed to the original payment method within 5 business days.
				Digital goods are non-refundable once downloaded.
				""").repeat(8).getBytes();

		mockMvc.perform(multipart("/api/rag/documents")
						.file(new MockMultipartFile("file", "policy.txt", "text/plain", body))
						.header("X-Tenant-Id", tenant))
				.andExpect(status().isCreated());
		drainIngestionQueue();

		String json = mockMvc.perform(post("/api/rag/query")
						.header("X-Tenant-Id", tenant)
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
						.header("X-Tenant-Id", "empty-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"anything?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.grounded").value(false))
				.andExpect(jsonPath("$.citations.length()").value(0));
	}
}

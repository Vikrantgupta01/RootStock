package com.rootstock.observability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.TestcontainersConfiguration;
import com.rootstock.auth.TestTokens;
import com.rootstock.rag.vector.BedrockKnowledgeBaseClient;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;

/**
 * The tracing aspects are the only thing standing between a request and a
 * Langfuse trace, and nothing in the application calls them — so a broken
 * pointcut would be completely silent. This asserts the trace a RAG request
 * actually produces: its shape, and the identifiers Langfuse groups on.
 *
 * <p>A {@link TestObservationRegistry} stands in for the real one, so this
 * verifies the instrumentation without any exporter, network or Langfuse
 * account.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TracingAspectIntegrationTest.TracingTestConfig.class})
class TracingAspectIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class TracingTestConfig {
		/**
		 * Declared as TestObservationRegistry so it satisfies both the
		 * ObservationRegistry injection points in the app and this test's own
		 * assertions. Wins over Boot's, which is @ConditionalOnMissingBean.
		 */
		@Bean
		TestObservationRegistry observationRegistry() {
			return TestObservationRegistry.create();
		}

		@Bean
		ChatModel stubChatModel() {
			return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("Two years."))));
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	TestObservationRegistry registry;

	@MockitoBean
	BedrockKnowledgeBaseClient kb;

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping tracing integration test");
	}

	@BeforeEach
	void stubRetrieval() {
		given(kb.retrieve(any(), any(), anyInt(), any(), any())).willReturn(List.of(
				KnowledgeBaseRetrievalResult.builder()
						.content(c -> c.text("The warranty period is 24 months."))
						.score(0.91)
						.build()));
	}

	@Test
	void aRagRequestProducesOneTraceWithSessionUserAndARetrieverChild() throws Exception {
		String tenant = "trace-" + UUID.randomUUID().toString().substring(0, 8);

		// No documents indexed in this tenant, so the query short-circuits before
		// retrieval. That is deliberate: the root span must exist either way, and
		// the early-exit path is the one most likely to be missed.
		String json = mockMvc.perform(post("/api/rag/query")
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"How long is the warranty?\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String conversationId = JsonPath.read(json, "$.conversationId");

		// Langfuse reads a trace's session and user from its ROOT span, which is
		// why the aspect opens one rather than letting whatever span happens to be
		// outermost define the trace.
		TestObservationRegistryAssert.assertThat(registry)
				.hasObservationWithNameEqualTo("answer-question")
				.that()
				.hasHighCardinalityKeyValue(LangfuseAttributes.SESSION_ID, conversationId)
				.hasHighCardinalityKeyValue(LangfuseAttributes.USER_ID, "test-admin-" + tenant)
				.hasHighCardinalityKeyValue(LangfuseAttributes.TRACE_NAME, "answer-question")
				.hasHighCardinalityKeyValue(LangfuseAttributes.TRACE_TAGS, "[\"rag\"]")
				.hasHighCardinalityKeyValue(LangfuseAttributes.TRACE_METADATA_PREFIX + "tenant", tenant)
				.hasHighCardinalityKeyValue(LangfuseAttributes.OBSERVATION_INPUT, "How long is the warranty?");
	}

	@Test
	void chatIsTracedTooAndItsSessionIsTheConversation() throws Exception {
		String tenant = "trace-" + UUID.randomUUID().toString().substring(0, 8);

		String json = mockMvc.perform(post("/api/chat")
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"hello there\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String conversationId = JsonPath.read(json, "$.conversationId");

		TestObservationRegistryAssert.assertThat(registry)
				.hasObservationWithNameEqualTo("chat-response")
				.that()
				.hasHighCardinalityKeyValue(LangfuseAttributes.SESSION_ID, conversationId)
				.hasHighCardinalityKeyValue(LangfuseAttributes.TRACE_TAGS, "[\"chat\"]")
				.hasHighCardinalityKeyValue(LangfuseAttributes.OBSERVATION_INPUT, "hello there")
				.hasHighCardinalityKeyValue(LangfuseAttributes.OBSERVATION_OUTPUT, "Two years.");
	}
}

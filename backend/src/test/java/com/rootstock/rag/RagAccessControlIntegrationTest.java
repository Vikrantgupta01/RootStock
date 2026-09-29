package com.rootstock.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.TestcontainersConfiguration;
import com.rootstock.auth.CognitoService;
import com.rootstock.auth.TestTokens;
import com.rootstock.rag.blob.BlobStore;
import com.rootstock.rag.ingest.IngestionService;
import com.rootstock.rag.vector.BedrockKnowledgeBaseClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import software.amazon.awssdk.services.bedrockagent.model.IngestionJobStatistics;
import software.amazon.awssdk.services.bedrockagentruntime.model.FilterAttribute;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;

/**
 * Permission enforcement end to end: who may call what (role), and which
 * documents a query is even allowed to retrieve over (access groups).
 *
 * <p>The group boundary itself is enforced inside Bedrock, which is mocked here,
 * so these tests assert the two things this application is actually responsible
 * for: that the {@code access_groups} attribute reaches the S3 sidecar Bedrock
 * indexes, and that the {@link RetrievalFilter} sent with every query restricts
 * the caller to their own groups. A live end-to-end pass against the real
 * Knowledge Base is a separate manual step (see TESTING.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RagAccessControlIntegrationTest.StubChatModelConfig.class})
class RagAccessControlIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class StubChatModelConfig {
		@Bean
		ChatModel stubChatModel() {
			return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("stub"))));
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	IngestionService ingestionService;

	@Autowired
	BlobStore blobStore;

	@MockitoBean
	BedrockKnowledgeBaseClient kb;

	/** Group creation reaches Cognito; nothing here should make a real AWS call. */
	@MockitoBean
	CognitoService cognito;

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping RAG access control integration test");
	}

	@BeforeEach
	void stubBedrock() {
		given(kb.sync(any(), any())).willReturn(IngestionJobStatistics.builder()
				.numberOfDocumentsScanned(1L).numberOfDocumentsFailed(0L).build());
		given(kb.retrieve(eq("test-kb"), any(), anyInt(), any(), any())).willReturn(List.of());
	}

	@Test
	void everyRagRouteNeedsAToken() throws Exception {
		mockMvc.perform(get("/api/rag/documents")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/rag/query")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"anything?\"}"))
				.andExpect(status().isUnauthorized());
		// Liveness stays open so the UI can tell "backend down" from "not signed in".
		mockMvc.perform(get("/api/health")).andExpect(status().isOk());
	}

	@Test
	void viewersMayQueryButNotChangeTheKnowledgeBase() throws Exception {
		String tenant = tenant();
		MockMultipartFile file = file("viewer-attempt.txt", "Anything at all.");

		mockMvc.perform(multipart("/api/rag/documents").file(file).with(TestTokens.viewer(tenant)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/rag/documents").with(TestTokens.viewer(tenant)))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/rag/query")
						.with(TestTokens.viewer(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"anything?\"}"))
				.andExpect(status().isOk());

		// Tuning and access administration are ADMIN-only.
		mockMvc.perform(post("/api/rag/profiles")
						.with(TestTokens.viewer(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"nope\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/rag/access-groups")
						.with(TestTokens.viewer(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"hr-only\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void anUngrantedDocumentIsTaggedPublicAndAGrantedOneIsTaggedWithItsGroups() throws Exception {
		String tenant = tenant();
		String uploadJson = mockMvc.perform(multipart("/api/rag/documents")
						.file(file("handbook.txt", "Vacation accrues at 1.5 days per month."))
						.with(TestTokens.admin(tenant)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID documentId = UUID.fromString(JsonPath.read(uploadJson, "$.documentId"));
		String sidecarKey = "rag-kb/" + tenant + "/" + documentId + "/v1.metadata.json";

		assertThat(sidecar(sidecarKey))
				.contains("\"access_groups\"")
				.contains("\"STRING_LIST\"")
				.contains("\"__public__\"");

		mockMvc.perform(post("/api/rag/access-groups")
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"hr-only\",\"description\":\"HR\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("hr-only"));
		verify(cognito).createGroup("hr-only", "HR");

		mockMvc.perform(put("/api/rag/documents/{id}/access-groups", documentId)
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"groups\":[\"hr-only\"]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessGroups[0]").value("hr-only"))
				// The grant only takes effect once Bedrock re-reads the sidecar.
				.andExpect(jsonPath("$.versions[0].status").value("PENDING"));

		assertThat(sidecar(sidecarKey)).contains("\"hr-only\"").doesNotContain("__public__");

		// Clearing the grants restores tenant-wide visibility.
		mockMvc.perform(put("/api/rag/documents/{id}/access-groups", documentId)
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"groups\":[]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessGroups.length()").value(0));
		assertThat(sidecar(sidecarKey)).contains("\"__public__\"").doesNotContain("hr-only");
	}

	@Test
	void queriesAreFilteredToTheCallersGroupsUnlessTheyAreAnAdmin() throws Exception {
		String tenant = tenant();
		mockMvc.perform(multipart("/api/rag/documents")
						.file(file("payroll.txt", "Salary bands are reviewed each March."))
						.with(TestTokens.admin(tenant)))
				.andExpect(status().isCreated());
		drainIngestionQueue();

		query(tenant, TestTokens.viewer(tenant, "hr-only"));
		// Their own group, plus the marker every tenant-wide document carries.
		assertThat(clausesOf(lastRetrievalFilter()))
				.contains("equals:tenant_id=" + tenant)
				.contains("listContains:access_groups=hr-only")
				.contains("listContains:access_groups=__public__");

		query(tenant, TestTokens.admin(tenant));
		// No group restriction for an admin -- but tenant scoping is never optional.
		assertThat(clausesOf(lastRetrievalFilter()))
				.contains("equals:tenant_id=" + tenant)
				.noneMatch(clause -> clause.startsWith("listContains:"));
	}

	/**
	 * Flattens a filter tree into readable {@code operator:key=value} clauses. The
	 * SDK's own {@code toString} redacts filter values as sensitive, so assertions
	 * have to walk the structure.
	 */
	private static List<String> clausesOf(RetrievalFilter filter) {
		List<String> clauses = new ArrayList<>();
		collectClauses(filter, clauses);
		return clauses;
	}

	private static void collectClauses(RetrievalFilter filter, List<String> into) {
		if (filter.hasAndAll()) {
			filter.andAll().forEach(child -> collectClauses(child, into));
		}
		else if (filter.hasOrAll()) {
			filter.orAll().forEach(child -> collectClauses(child, into));
		}
		else if (filter.equalsValue() != null) {
			into.add("equals:" + describe(filter.equalsValue()));
		}
		else if (filter.listContains() != null) {
			into.add("listContains:" + describe(filter.listContains()));
		}
		else if (filter.in() != null) {
			into.add("in:" + filter.in().key());
		}
	}

	private static String describe(FilterAttribute attribute) {
		return attribute.key() + "=" + attribute.value().asString();
	}

	private void query(String tenant, org.springframework.test.web.servlet.request.RequestPostProcessor caller)
			throws Exception {
		mockMvc.perform(post("/api/rag/query")
						.with(caller)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"What are the salary bands?\"}"))
				.andExpect(status().isOk());
	}

	private RetrievalFilter lastRetrievalFilter() {
		ArgumentCaptor<RetrievalFilter> captor = ArgumentCaptor.forClass(RetrievalFilter.class);
		verify(kb, org.mockito.Mockito.atLeastOnce())
				.retrieve(eq("test-kb"), any(), anyInt(), captor.capture(), any());
		return captor.getValue();
	}

	private String sidecar(String key) {
		try (InputStream in = blobStore.get(key)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Could not read sidecar " + key, ex);
		}
	}

	private void drainIngestionQueue() {
		for (UUID jobId : ingestionService.claim(10)) {
			ingestionService.process(jobId);
		}
	}

	private static String tenant() {
		return "acl-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static MockMultipartFile file(String name, String content) {
		return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
	}
}

package com.rootstock.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.TestcontainersConfiguration;
import com.rootstock.rag.blob.BlobStore;
import com.rootstock.rag.document.DocumentStatus;
import com.rootstock.rag.document.DocumentVersion;
import com.rootstock.rag.document.DocumentVersionRepository;
import com.rootstock.rag.ingest.IngestionService;
import com.rootstock.rag.vector.BedrockKnowledgeBaseClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJobStatistics;

/**
 * End-to-end: upload -> ingest -> a Bedrock Knowledge Base sync is triggered and
 * the version flips to INDEXED -> tenant isolation -> delete purges the KB copy
 * and triggers a cleanup sync. {@link BedrockKnowledgeBaseClient} is mocked --
 * there is no local/Testcontainers stand-in for a real Knowledge Base -- so this
 * exercises the document/version/job bookkeeping (Testcontainers Postgres) and the
 * S3-sidecar contract, not Bedrock itself. Skips when Docker is unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RagIngestionIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	IngestionService ingestionService;

	@Autowired
	DocumentVersionRepository versions;

	@Autowired
	BlobStore blobStore;

	@MockitoBean
	BedrockKnowledgeBaseClient kb;

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping RAG ingestion integration test");
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

	private static String kbObjectKey(String tenantId, UUID documentId, UUID versionId) {
		return "rag-kb/" + tenantId + "/" + documentId + "/" + versionId;
	}

	@Test
	void uploadIsIndexedIsolatedAndPurgeable() throws Exception {
		String tenant = "acme-" + UUID.randomUUID().toString().substring(0, 8);
		byte[] body = ("""
				Vacation policy: employees accrue 1.5 days of paid vacation per month.
				Expense reimbursement: submit receipts within 30 days. Meals up to 75 USD per day.
				Security: enable multi-factor authentication on all company accounts.
				""").repeat(20).getBytes();
		MockMultipartFile file = new MockMultipartFile("file", "handbook.txt", "text/plain", body);

		String uploadJson = mockMvc.perform(multipart("/api/rag/documents").file(file).header("X-Tenant-Id", tenant))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version.status").value("PENDING"))
				.andReturn().getResponse().getContentAsString();
		UUID documentId = UUID.fromString(JsonPath.read(uploadJson, "$.documentId"));
		UUID versionId = UUID.fromString(JsonPath.read(uploadJson, "$.version.id"));

		String v1Key = kbObjectKey(tenant, documentId, versionId);
		assertThat(blobStore.exists(v1Key)).isTrue();
		assertThat(blobStore.exists(v1Key + ".metadata.json")).isTrue();

		drainIngestionQueue();
		verify(kb, times(1)).sync("test-kb", "test-ds");

		DocumentVersion v1 = versions.findById(versionId).orElseThrow();
		assertThat(v1.getStatus()).isEqualTo(DocumentStatus.INDEXED);
		assertThat(v1.getIndexedAt()).isNotNull();

		// another tenant sees nothing
		mockMvc.perform(get("/api/rag/documents").header("X-Tenant-Id", "someone-else"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.totalElements").value(0));

		// new version indexes independently, activation flips the pointer
		MockMultipartFile v2File = new MockMultipartFile("file", "handbook.txt", "text/plain",
				"Updated: vacation is now 20 days per year.".getBytes());
		mockMvc.perform(multipart("/api/rag/documents/{id}/versions", documentId).file(v2File)
						.header("X-Tenant-Id", tenant))
				.andExpect(status().isCreated());
		drainIngestionQueue();
		verify(kb, times(2)).sync("test-kb", "test-ds");

		mockMvc.perform(post("/api/rag/documents/{id}/versions/{n}/activate", documentId, 2)
						.header("X-Tenant-Id", tenant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activeVersionId").isNotEmpty());

		List<DocumentVersion> allVersions = versions.findByDocumentIdOrderByVersionNoDesc(documentId);
		assertThat(allVersions).hasSize(2).allMatch(v -> v.getStatus() == DocumentStatus.INDEXED);

		// deleting the document purges the KB copy of every version and queues a cleanup sync each
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.delete("/api/rag/documents/{id}", documentId).header("X-Tenant-Id", tenant))
				.andExpect(status().isNoContent());
		assertThat(blobStore.exists(v1Key)).isFalse();

		drainIngestionQueue();
		verify(kb, times(4)).sync("test-kb", "test-ds"); // 2 ingests + 2 per-version cleanups
	}
}

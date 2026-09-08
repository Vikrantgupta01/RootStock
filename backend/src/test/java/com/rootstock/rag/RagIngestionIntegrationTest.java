package com.rootstock.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.TestcontainersConfiguration;
import com.rootstock.rag.document.DocumentStatus;
import com.rootstock.rag.document.DocumentVersion;
import com.rootstock.rag.document.DocumentVersionRepository;
import com.rootstock.rag.ingest.IngestionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

/**
 * End-to-end Phase 1: upload -> ingest (fake embeddings, filesystem blobs,
 * Testcontainers Postgres) -> chunks land in vector_store_1024 -> tenant
 * isolation -> delete purges. Skips when Docker is unavailable.
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
	JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping RAG ingestion integration test");
	}

	private void drainIngestionQueue() {
		for (UUID jobId : ingestionService.claim(10)) {
			ingestionService.process(jobId);
		}
	}

	private long chunkCount(String tenantId) {
		Long n = jdbcTemplate.queryForObject(
				"select count(*) from vector_store_1024 where metadata->>'tenant_id' = ?", Long.class, tenantId);
		return n == null ? 0 : n;
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

		drainIngestionQueue();

		DocumentVersion v1 = versions.findById(versionId).orElseThrow();
		assertThat(v1.getStatus()).isEqualTo(DocumentStatus.INDEXED);
		assertThat(v1.getChunkCount()).isGreaterThan(1);
		assertThat(v1.getIndexedAt()).isNotNull();
		assertThat(chunkCount(tenant)).isEqualTo(v1.getChunkCount());

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

		mockMvc.perform(post("/api/rag/documents/{id}/versions/{n}/activate", documentId, 2)
						.header("X-Tenant-Id", tenant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activeVersionId").isNotEmpty());

		List<DocumentVersion> allVersions = versions.findByDocumentIdOrderByVersionNoDesc(documentId);
		assertThat(allVersions).hasSize(2).allMatch(v -> v.getStatus() == DocumentStatus.INDEXED);

		// deleting the document purges every chunk for the tenant
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.delete("/api/rag/documents/{id}", documentId).header("X-Tenant-Id", tenant))
				.andExpect(status().isNoContent());
		assertThat(chunkCount(tenant)).isZero();
	}
}

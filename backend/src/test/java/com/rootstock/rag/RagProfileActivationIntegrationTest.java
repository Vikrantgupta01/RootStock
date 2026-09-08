package com.rootstock.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.TestcontainersConfiguration;
import com.rootstock.rag.ingest.IngestionService;
import com.rootstock.rag.profile.RagProfileService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

/**
 * Blue/green profile activation: a chunking change re-indexes under the target
 * profile while the previous one keeps serving, then the monitor flips the
 * pointer and the old layout's chunks are cleaned up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RagProfileActivationIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	IngestionService ingestionService;

	@Autowired
	RagProfileService profileService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping blue/green activation test");
	}

	private void drainIngestionQueue() {
		for (UUID jobId : ingestionService.claim(20)) {
			ingestionService.process(jobId);
		}
	}

	@SuppressWarnings("unchecked")
	private static String firstString(String json, String path) {
		return ((List<String>) JsonPath.read(json, path)).get(0);
	}

	/** Version number of the tenant's active `default` profile. */
	private int activeVersionNo(String tenant) throws Exception {
		String json = mockMvc.perform(get("/api/rag/profiles").header("X-Tenant-Id", tenant))
				.andReturn().getResponse().getContentAsString();
		List<Integer> versions = JsonPath.read(json, "$[?(@.name=='default' && @.active==true)].versionNo");
		assertThat(versions).hasSize(1);
		return versions.get(0);
	}

	private Map<String, Long> chunkConfigCounts(String tenant) {
		return jdbcTemplate.query(
				"select metadata->>'chunk_config' cfg, count(*) n from vector_store_1024 "
						+ "where metadata->>'tenant_id' = ? group by cfg",
				rs -> {
					var m = new java.util.HashMap<String, Long>();
					while (rs.next()) {
						m.put(rs.getString("cfg"), rs.getLong("n"));
					}
					return m;
				},
				tenant);
	}

	@Test
	void chunkingChangeRunsBlueGreenReindex() throws Exception {
		String tenant = "bg-" + UUID.randomUUID().toString().substring(0, 8);
		byte[] body = ("RootStock refunds are available within 14 days. "
				+ "Enterprise contracts may define custom refund terms. ").repeat(40).getBytes();

		mockMvc.perform(multipart("/api/rag/documents")
						.file(new MockMultipartFile("file", "policy.txt", "text/plain", body))
						.header("X-Tenant-Id", tenant))
				.andExpect(status().isCreated());
		drainIngestionQueue();
		assertThat(chunkConfigCounts(tenant)).containsKey("CHARACTER:1200:150");

		String defaultId = firstString(
				mockMvc.perform(get("/api/rag/profiles").header("X-Tenant-Id", tenant))
						.andReturn().getResponse().getContentAsString(),
				"$[?(@.name=='default')].id");

		String v2Json = mockMvc.perform(post("/api/rag/profiles/{id}/versions", defaultId)
						.header("X-Tenant-Id", tenant)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"chunkSize\":400,\"chunkOverlap\":50}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String v2Id = JsonPath.read(v2Json, "$.id");

		String activationJson = mockMvc.perform(post("/api/rag/profiles/{id}/activate", v2Id)
						.header("X-Tenant-Id", tenant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reindexRequired").value(true))
				.andExpect(jsonPath("$.state").value("PENDING"))
				.andExpect(jsonPath("$.totalReindexJobs").value(1))
				.andReturn().getResponse().getContentAsString();

		// previous profile still active until the re-index completes
		assertThat(activeVersionNo(tenant)).isEqualTo(1);

		drainIngestionQueue(); // run the REINDEX job (writes CHARACTER:400:50)
		profileService.finalizePendingActivations(); // monitor flips the pointer + queues CLEANUP
		drainIngestionQueue(); // run the CLEANUP job (drops CHARACTER:1200:150)

		assertThat(activeVersionNo(tenant)).isEqualTo(2);

		String activationId = JsonPath.read(activationJson, "$.activationId");
		mockMvc.perform(get("/api/rag/profiles/activations/{id}", activationId).header("X-Tenant-Id", tenant))
				.andExpect(jsonPath("$.state").value("COMPLETED"));

		Map<String, Long> counts = chunkConfigCounts(tenant);
		assertThat(counts).containsKey("CHARACTER:400:50");
		assertThat(counts).doesNotContainKey("CHARACTER:1200:150");
	}
}

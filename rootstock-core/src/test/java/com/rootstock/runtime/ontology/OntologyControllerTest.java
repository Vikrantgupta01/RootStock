package com.rootstock.runtime.ontology;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyLoader;
import com.rootstock.core.pack.PackRegistry;
import com.rootstock.runtime.common.GlobalExceptionHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// Security is out of this slice, as in the other controller tests; the admin-only
// reload (@PreAuthorize) is checked against the running app.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(OntologyController.class)
@Import({ GlobalExceptionHandler.class, OntologyControllerTest.Packs.class })
class OntologyControllerTest {

	@Autowired
	MockMvc mockMvc;

	/** A real registry over two packs on disk: the sample domain, and a broken copy of it. */
	@TestConfiguration
	static class Packs {

		@Bean
		PackRegistry packRegistry() throws IOException {
			Ontology core = new OntologyLoader().parse(read("/ontology/rootstock-core.yaml"));
			String sample = read("/ontology/sample-pack.yaml");
			Path root = Files.createTempDirectory("packs");
			root.toFile().deleteOnExit();
			Files.writeString(Files.createDirectories(root.resolve("repairs")).resolve("ontology.yaml"), sample);
			Files.writeString(Files.createDirectories(root.resolve("broken")).resolve("ontology.yaml"),
					sample.replace("name: repairs", "name: broken").replace("vocab: Trade, required: true }\n      room",
							"vocab: Trades, required: true }\n      room"));
			return new PackRegistry(core, List.of(root));
		}

		private static String read(String resource) throws IOException {
			try (InputStream in = Packs.class.getResourceAsStream(resource)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
	}

	@Test
	void overviewListsValidAndBrokenPacks() throws Exception {
		mockMvc.perform(get("/api/ontology"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.core.name").value("rootstock-core"))
				.andExpect(jsonPath("$.packs[0].name").value("broken"))
				.andExpect(jsonPath("$.packs[0].status").value("INVALID"))
				.andExpect(jsonPath("$.packs[0].problems[0].at").value("entities.Defect.attributes.trade.vocab"))
				.andExpect(jsonPath("$.packs[1].name").value("repairs"))
				.andExpect(jsonPath("$.packs[1].ontology.concepts").value(6));
	}

	@Test
	void aPackShowsConceptsWithInheritedFieldsMarked() throws Exception {
		mockMvc.perform(get("/api/ontology/packs/repairs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ontology.entities[0].name").value("Job"))
				.andExpect(jsonPath("$.ontology.entities[0].ancestors[0]").value("core.Case"))
				.andExpect(jsonPath("$.ontology.entities[0].relations[1].to").value("Defect"))
				.andExpect(jsonPath("$.ontology.vocabularies[0].terms[0].code").value("PLUMBING"))
				.andExpect(jsonPath("$.ontology.constraints[0].id").value("hazards-are-urgent"));
	}

	@Test
	void aBrokenPackHasProblemsButNoOntology() throws Exception {
		mockMvc.perform(get("/api/ontology/packs/broken"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pack.status").value("INVALID"))
				.andExpect(jsonPath("$.ontology").doesNotExist());
	}

	@Test
	void aProjectionReturnsItsSchemaAndGlossary() throws Exception {
		mockMvc.perform(get("/api/ontology/packs/repairs/projections/job-intake"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.schema.title").value("Job"))
				.andExpect(jsonPath("$.schema.properties.defects.minItems").value(1))
				.andExpect(jsonPath("$.glossary").value(org.hamcrest.Matchers.containsString("- URGENT:")));
	}

	@Test
	void unknownPackOrProjectionIs404() throws Exception {
		mockMvc.perform(get("/api/ontology/packs/nope")).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/ontology/packs/repairs/projections/nope")).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/ontology/packs/broken/projections/job-intake")).andExpect(status().isNotFound());
	}

	@Test
	void theCoreOntologyIsViewable() throws Exception {
		mockMvc.perform(get("/api/ontology/core"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entities.length()").value(7));
	}

	@Test
	void reloadReturnsTheFreshOverview() throws Exception {
		mockMvc.perform(post("/api/ontology/reload"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.packs.length()").value(2));
	}
}

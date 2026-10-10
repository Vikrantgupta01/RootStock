package com.rootstock.core.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.testsupport.Snapshots;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonSchemaGeneratorTest {

	private static final Path SNAPSHOTS = Path.of("src/test/resources/ontology/snapshots");

	private final JsonSchemaGenerator generator = new JsonSchemaGenerator();

	@Test
	void jobIntakeSchemaMatchesTheSnapshot() {
		Snapshots.assertMatches(SNAPSHOTS.resolve("job-intake.schema.json"),
				generator.generateJson(OntologyFixtures.sample(), "job-intake"));
	}

	@Test
	void dispatchViewSchemaMatchesTheSnapshot() {
		Snapshots.assertMatches(SNAPSHOTS.resolve("dispatch-view.schema.json"),
				generator.generateJson(OntologyFixtures.sample(), "dispatch-view"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void embedsReferencesAndLeavesOutTheRest() {
		Map<String, Object> schema = generator.generate(OntologyFixtures.sample(), "job-intake");
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");

		assertThat(props).containsOnlyKeys("priority", "hazards", "reportedOn", "tenant", "defects", "visits", "propertyRef");
		assertThat((List<String>) schema.get("required")).containsExactly("priority", "reportedOn", "tenant", "defects", "propertyRef");
		assertThat(props.get("defects")).isEqualTo(Map.of("type", "array", "minItems", 1, "items", Map.of("$ref", "#/$defs/Defect")));
		assertThat((Map<String, Object>) schema.get("$defs")).containsOnlyKeys("Tenant", "Defect", "Visit");

		// Visit.fixes points back at a Defect that is already embedded under the Job: left out, no cycle.
		Map<String, Object> visit = (Map<String, Object>) ((Map<String, Object>) schema.get("$defs")).get("Visit");
		assertThat((Map<String, Object>) visit.get("properties")).containsOnlyKeys("trade", "estimateAud", "contractorRef");
	}

	@Test
	@SuppressWarnings("unchecked")
	void includeNarrowsFieldsButKeepsReferences() {
		Map<String, Object> schema = generator.generate(OntologyFixtures.sample(), "dispatch-view");

		assertThat((Map<String, Object>) schema.get("properties")).containsOnlyKeys("priority", "defects", "propertyRef");
		Map<String, Object> defect = (Map<String, Object>) ((Map<String, Object>) schema.get("$defs")).get("Defect");
		assertThat((Map<String, Object>) defect.get("properties")).containsOnlyKeys("trade");
	}
}

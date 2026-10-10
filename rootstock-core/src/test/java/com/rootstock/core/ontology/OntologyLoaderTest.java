package com.rootstock.core.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OntologyLoaderTest {

	private final OntologyLoader loader = new OntologyLoader();

	@Test
	void theCoreOntologyLoadsAndIsValid() {
		Ontology core = OntologyFixtures.core();

		assertThat(core.name()).isEqualTo("rootstock-core");
		assertThat(core.extendsName()).isNull();
		assertThat(core.entities()).containsOnlyKeys("Party", "Document", "Case", "Request", "Action", "Issue", "Approval");
		assertThat(core.vocabularies().get("IssueSeverity").codes()).containsExactly("BLOCKING", "WARNING");
		assertThat(core.entities().get("Issue").origin()).isEqualTo("rootstock-core");
		assertThat(new OntologyValidator().validateCore(core)).isEmpty();
	}

	@Test
	void aPackKeepsDeclarationOrderAndReadsEveryPart() {
		Ontology pack = loader.parse(OntologyFixtures.sampleText());

		assertThat(pack.entities().keySet()).containsExactly("Job", "Tenant", "Property", "Defect", "Visit", "Contractor");
		Entity job = pack.entities().get("Job");
		assertThat(job.extendsRef()).isEqualTo("core.Case");
		assertThat(job.attributes()).extracting(Attribute::name).containsExactly("priority", "hazards", "reportedOn");
		assertThat(job.relations().get(1)).isEqualTo(new Relation("finds", "Defect", "defects", false, true, 1, null, null));
		assertThat(pack.vocabularies().get("Trade").description()).isEqualTo("The kind of tradesperson a defect needs.");
		assertThat(pack.vocabularies().get("Trade").terms().get(0).synonyms()).contains("blocked drain");
		assertThat(pack.constraints().get(0).when()).containsEntry("Job.hazards", "notEmpty");
		assertThat(pack.projections().get("dispatch-view").include()).containsExactly("priority", "defects.trade");
	}

	@Test
	void aTypoInAKeyIsReportedWithWhereItIs() {
		String yaml = OntologyFixtures.sampleText().replace("priority: { vocab: Priority, required: true }",
				"priority: { vocab: Priority, requird: true }");

		assertThatThrownBy(() -> loader.parse(yaml)).isInstanceOf(OntologyException.class)
				.hasMessageContaining("entities.Job.attributes.priority.requird: unknown key 'requird'");
	}

	@Test
	void everyShapeProblemIsReportedTogether() {
		String yaml = """
				apiVersion: rootstock/v2
				kind: Ontology
				metadata: { name: broken }
				entities:
				  Job:
				    attributes:
				      count: { type: integer, min: lots }
				      done: { type: boolean, required: maybe }
				""";

		assertThatThrownBy(() -> loader.parse(yaml)).isInstanceOfSatisfying(OntologyException.class,
				e -> assertThat(e.problems()).extracting(OntologyProblem::toString).containsExactlyInAnyOrder(
						"apiVersion: expected rootstock/v1, found rootstock/v2",
						"entities.Job.attributes.count.min: expected a number, found 'lots'",
						"entities.Job.attributes.done.required: expected true or false, found 'maybe'"));
	}

	@Test
	void invalidYamlNamesTheLine() {
		String yaml = "apiVersion: rootstock/v1\nkind: Ontology\nentities:\n  Job: { attributes: [\n";

		assertThatThrownBy(() -> loader.parse(yaml)).isInstanceOf(OntologyException.class)
				.hasMessageContaining("line ").hasMessageContaining("not valid YAML");
	}

	@Test
	void aDuplicateKeyIsAnErrorNotASilentOverwrite() {
		String yaml = "apiVersion: rootstock/v1\nkind: Ontology\nmetadata: { name: x }\nentities:\n  Job: {}\n  Job: {}\n";

		assertThatThrownBy(() -> loader.parse(yaml)).isInstanceOf(OntologyException.class)
				.hasMessageContaining("duplicate key Job");
	}
}

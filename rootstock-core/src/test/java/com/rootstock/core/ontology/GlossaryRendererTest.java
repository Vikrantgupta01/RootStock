package com.rootstock.core.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.testsupport.Snapshots;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GlossaryRendererTest {

	private final GlossaryRenderer renderer = new GlossaryRenderer();

	@Test
	void jobIntakeGlossaryMatchesTheSnapshot() {
		Snapshots.assertMatches(Path.of("src/test/resources/ontology/snapshots/job-intake.glossary.txt"),
				renderer.render(OntologyFixtures.sample(), "job-intake"));
	}

	@Test
	void aProjectionOnlyGetsTheVocabulariesItsFieldsUse() {
		String glossary = renderer.render(OntologyFixtures.sample(), "dispatch-view");

		assertThat(glossary).contains("Priority", "Trade").doesNotContain("Hazard");
		assertThat(glossary).contains("- PLUMBING: Water, taps, toilets, drains and hot water. Also said as: leak, blocked drain, no hot water.");
		assertThat(glossary).contains("Used for Defect.trade.");
	}
}

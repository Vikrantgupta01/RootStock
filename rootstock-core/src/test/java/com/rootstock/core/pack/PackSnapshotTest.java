package com.rootstock.core.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rootstock.core.ontology.GlossaryRenderer;
import com.rootstock.core.ontology.JsonSchemaGenerator;
import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyLoader;
import com.rootstock.core.ontology.ResolvedOntology;
import com.rootstock.testsupport.Snapshots;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Snapshot test for the domain packs Rootstock is configured with
 * ({@code ROOTSTOCK_PACKS_PATHS}, loaded from {@code .env}): every pack's
 * ontology must be valid, and its committed {@code generated/} schema and
 * glossary for each projection must match what Rootstock generates now. Skipped
 * when no packs are configured. After an intended ontology change, rewrite
 * them with {@code mvn test -Dtest=PackSnapshotTest -Dsnapshot.update=true}.
 */
class PackSnapshotTest {

	@Test
	void everyConfiguredPackIsValidAndItsGeneratedFilesAreCurrent() throws IOException {
		String configured = System.getProperty("rootstock.packs.paths", System.getenv("ROOTSTOCK_PACKS_PATHS"));
		assumeTrue(configured != null && !configured.isBlank(), "ROOTSTOCK_PACKS_PATHS is not set");
		List<Path> paths = Arrays.stream(configured.split(",")).map(String::strip).filter(s -> !s.isEmpty())
				.map(Path::of).toList();

		Ontology core;
		try (InputStream in = getClass().getResourceAsStream("/ontology/rootstock-core.yaml")) {
			core = new OntologyLoader().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		PackLoader.Result result = new PackLoader(core).load(paths);
		assertThat(result.pathProblems()).isEmpty();

		for (LoadedPack pack : result.packs()) {
			assertThat(pack.problems()).as("problems in pack %s", pack.name()).isEmpty();
			if (pack.status() != LoadedPack.Status.VALID) {
				continue;
			}
			ResolvedOntology ontology = new ResolvedOntology(core, pack.ontology());
			Path generated = Path.of(pack.location(), "generated");
			for (String projection : pack.ontology().projections().keySet()) {
				Snapshots.assertMatches(generated.resolve(projection + ".schema.json"),
						new JsonSchemaGenerator().generateJson(ontology, projection));
				Snapshots.assertMatches(generated.resolve(projection + ".glossary.txt"),
						new GlossaryRenderer().render(ontology, projection));
			}
		}
	}
}

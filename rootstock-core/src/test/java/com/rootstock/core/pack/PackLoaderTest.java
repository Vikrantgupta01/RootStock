package com.rootstock.core.pack;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackLoaderTest {

	@TempDir
	Path root;

	private Ontology core;
	private String sample;

	@BeforeEach
	void setUp() throws IOException {
		core = new OntologyLoader().parse(read("/ontology/rootstock-core.yaml"));
		sample = read("/ontology/sample-pack.yaml");
	}

	private static String read(String resource) throws IOException {
		try (InputStream in = PackLoaderTest.class.getResourceAsStream(resource)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private Path pack(String name, String ontology) throws IOException {
		Path dir = Files.createDirectories(root.resolve("packs").resolve(name));
		Files.writeString(dir.resolve("tools.yaml"), "rootstock: {}\n");
		if (ontology != null) {
			Files.writeString(dir.resolve("ontology.yaml"), ontology);
		}
		return dir;
	}

	@Test
	void findsEveryPackInAFolderAndKeepsBrokenOnesWithTheirProblems() throws IOException {
		pack("repairs", sample);
		pack("broken", sample.replace("name: repairs", "name: broken").replace("fixes: { to: Defect }", "fixes: { to: Fault }"));
		pack("tools-only", null);
		Files.createDirectories(root.resolve("packs/not-a-pack"));

		PackLoader.Result result = new PackLoader(core).load(List.of(root.resolve("packs")));

		assertThat(result.pathProblems()).isEmpty();
		assertThat(result.packs()).extracting(LoadedPack::name, LoadedPack::status).containsExactly(
				org.assertj.core.groups.Tuple.tuple("broken", LoadedPack.Status.INVALID),
				org.assertj.core.groups.Tuple.tuple("repairs", LoadedPack.Status.VALID),
				org.assertj.core.groups.Tuple.tuple("tools-only", LoadedPack.Status.NO_ONTOLOGY));
		LoadedPack broken = result.packs().get(0);
		assertThat(broken.ontology()).isNull();
		assertThat(broken.problems()).anySatisfy(p -> assertThat(p.toString())
				.startsWith("entities.Visit.relations.fixes.to: unknown entity 'Fault'"));
		assertThat(result.packs().get(1).files()).containsExactly("ontology.yaml", "tools.yaml");
	}

	@Test
	void aPathCanNameOnePackDirectly() throws IOException {
		Path dir = pack("repairs", sample);

		assertThat(new PackLoader(core).load(List.of(dir)).packs()).singleElement()
				.satisfies(p -> assertThat(p.status()).isEqualTo(LoadedPack.Status.VALID));
	}

	@Test
	void unreadableYamlIsInvalidNotAnException() throws IOException {
		pack("repairs", "apiVersion: rootstock/v1\nentities: [\n");

		LoadedPack pack = new PackLoader(core).load(List.of(root.resolve("packs"))).packs().get(0);

		assertThat(pack.status()).isEqualTo(LoadedPack.Status.INVALID);
		assertThat(pack.problems().get(0).message()).startsWith("not valid YAML");
	}

	@Test
	void theOntologyMustBeNamedAfterItsFolder() throws IOException {
		pack("other", sample);

		LoadedPack pack = new PackLoader(core).load(List.of(root.resolve("packs"))).packs().get(0);

		assertThat(pack.status()).isEqualTo(LoadedPack.Status.INVALID);
		assertThat(pack.problems().get(0).toString())
				.isEqualTo("metadata.name: 'repairs' differs from the pack's folder name 'other'");
	}

	@Test
	void missingAndEmptyPathsAreReportedNotFatal() throws IOException {
		Files.createDirectories(root.resolve("empty"));

		PackLoader.Result result = new PackLoader(core).load(List.of(root.resolve("nowhere"), root.resolve("empty")));

		assertThat(result.packs()).isEmpty();
		assertThat(result.pathProblems()).hasSize(2);
		assertThat(result.pathProblems().get(0)).endsWith("nowhere is not a folder");
		assertThat(result.pathProblems().get(1)).startsWith("No packs in ");
	}

	@Test
	void reloadPicksUpAFixedPack() throws IOException {
		Path dir = pack("repairs", sample.replace("fixes: { to: Defect }", "fixes: { to: Fault }"));
		PackRegistry registry = new PackRegistry(core, List.of(root.resolve("packs")));
		assertThat(registry.ontology("repairs")).isEmpty();

		Files.writeString(dir.resolve("ontology.yaml"), sample);
		registry.reload();

		assertThat(registry.ontology("repairs")).isPresent();
	}
}

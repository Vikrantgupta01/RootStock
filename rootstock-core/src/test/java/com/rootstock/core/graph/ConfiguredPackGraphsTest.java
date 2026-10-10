package com.rootstock.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyLoader;
import com.rootstock.core.ontology.ResolvedOntology;
import com.rootstock.core.pack.LoadedPack;
import com.rootstock.core.pack.PackLoader;
import com.rootstock.core.tools.ToolAccess;
import com.rootstock.core.tools.ToolCatalog;
import com.rootstock.core.tools.ToolDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Every pack Rootstock is configured with ({@code ROOTSTOCK_PACKS_PATHS}, from
 * {@code .env}) that has a graph: it loads, passes every startup check against
 * the pack's own tools.yaml, and compiles. Skipped when
 * no packs are configured.
 */
class ConfiguredPackGraphsTest {

	@Test
	void everyConfiguredGraphIsValidAndRuns() throws IOException {
		String configured = System.getProperty("rootstock.packs.paths", System.getenv("ROOTSTOCK_PACKS_PATHS"));
		assumeTrue(configured != null && !configured.isBlank(), "ROOTSTOCK_PACKS_PATHS is not set");
		Ontology core;
		try (InputStream in = getClass().getResourceAsStream("/ontology/rootstock-core.yaml")) {
			core = new OntologyLoader().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		List<LoadedPack> packs = new PackLoader(core).load(Arrays.stream(configured.split(",")).map(String::strip)
				.filter(s -> !s.isEmpty()).map(Path::of).toList()).packs();

		int checked = 0;
		for (LoadedPack pack : packs) {
			Path dir = Path.of(pack.location());
			if (!PackGraphLoader.hasGraph(dir)) {
				continue;
			}
			PackGraph graph = new PackGraphLoader().load(pack.name(), dir);
			ResolvedOntology ontology = pack.ontology() == null ? null : new ResolvedOntology(core, pack.ontology());
			List<GraphProblem> problems = new GraphValidator().validate(graph, new GraphValidator.Context(
					RepairsPack.registry(), Set.of(), tools(dir.resolve("tools.yaml")), ontology, "not valid"));
			assertThat(problems).as("problems in pack %s", pack.name()).isEmpty();
			assertThat(new GraphCompiler(RepairsPack.registry(), List.of(), NodeListener.NONE).compile(graph, ontology)
					.compiled()).isNotNull();
			checked++;
		}
		assumeTrue(checked > 0, "no configured pack has a graph");
	}

	/** The tools and allowlists from a pack's tools.yaml, as Rootstock binds them. */
	@SuppressWarnings("unchecked")
	private static ToolCatalog tools(Path file) throws IOException {
		if (!Files.exists(file)) {
			return ToolCatalog.empty();
		}
		Map<String, Object> root = new Yaml().load(Files.readString(file));
		Map<String, Object> tools = (Map<String, Object>) ((Map<String, Object>) root.get("rootstock")).get("tools");
		Map<String, ToolDefinition> defs = new LinkedHashMap<>();
		for (Map<String, Object> t : (List<Map<String, Object>>) tools.get("tools")) {
			String name = (String) t.get("name");
			defs.put(name, new ToolDefinition(name, (String) t.get("connection"),
					(String) t.getOrDefault("remote-name", name), ToolAccess.valueOf((String) t.get("access"))));
		}
		return new ToolCatalog(defs, (Map<String, List<String>>) tools.get("allowlists"),
				new HashSet<>((List<String>) tools.get("write-nodes")));
	}
}

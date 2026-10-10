package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.cases.CaseGraphs;
import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.GraphDefinition;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.ontology.RequiredFields;
import com.rootstock.core.ontology.ResolvedOntology;
import com.rootstock.core.pack.LoadedPack;
import com.rootstock.core.pack.PackRegistry;
import com.rootstock.testsupport.ThrowawaySchemaConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Runs every structured-extraction agent of the configured packs on its sample
 * inputs ({@code <pack>/samples/<agent>/*.yaml}) against the real model and
 * prompt, and checks the records. Tagged {@code live}: each sample is a Bedrock
 * call ({@code mvn verify -Dlive.excluded=none -Dgroups=live}).
 *
 * <p>A sample file has {@code input} (the text submitted), {@code expect} (field
 * path to value; paths go through lists, a list value means exactly that set of
 * values) and {@code missing} (exactly the required fields the input lacks).
 */
@Tag("live")
@SpringBootTest
@Import(ThrowawaySchemaConfig.class)
class PackSamplesLiveIT {

	@Autowired
	CaseGraphs graphs;

	@Autowired
	PackRegistry packs;

	@Autowired
	StructuredExtraction extraction;

	@TestFactory
	Stream<DynamicTest> everySampleIsExtractedAsExpected() throws IOException {
		List<DynamicTest> tests = new ArrayList<>();
		for (CaseGraph graph : graphs.all()) {
			PackGraph definition = graph.definition();
			LoadedPack pack = packs.snapshot().packs().stream().filter(p -> p.name().equals(graph.pack())).findFirst()
					.orElseThrow();
			ResolvedOntology ontology = packs.ontology(pack.name()).orElseThrow();
			for (GraphDefinition.NodeSpec node : definition.graph().nodes()) {
				AgentDefinition agent = node.agent() == null ? null : definition.agents().get(node.agent());
				if (agent == null || !agent.spec().type().equals(StructuredExtraction.TYPE)) {
					continue;
				}
				Path samples = Path.of(pack.location(), "samples", agent.name());
				if (!Files.isDirectory(samples)) {
					continue;
				}
				NodeContext context = new NodeContext(pack.name(), node, agent, ontology, definition);
				try (Stream<Path> files = Files.list(samples)) {
					for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
						tests.add(DynamicTest.dynamicTest(pack.name() + "/" + agent.name() + "/" + file.getFileName(),
								() -> check(context, ontology, file)));
					}
				}
			}
		}
		return tests.stream();
	}

	@SuppressWarnings("unchecked")
	private void check(NodeContext context, ResolvedOntology ontology, Path file) throws Exception {
		Map<String, Object> sample = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
		Map<String, Object> update = extraction.create(context).apply(new CaseState(Map.of(CaseState.CASE_ID,
				"sample", CaseState.RAW_INPUT, sample.get("input"))));
		AgentDefinition.Output output = context.agent().spec().output();
		Object record = update.get(output.writeTo());

		List<String> wrong = new ArrayList<>();
		((Map<String, Object>) sample.getOrDefault("expect", Map.of())).forEach((path, expected) -> {
			Set<String> want = expected == null ? Set.of()
					: (expected instanceof List<?> l ? l.stream() : Stream.of(expected)).map(PackSamplesLiveIT::text)
							.collect(Collectors.toCollection(TreeSet::new));
			Set<String> got = values(record, path).stream().map(PackSamplesLiveIT::text)
					.collect(Collectors.toCollection(TreeSet::new));
			if (!want.equals(got)) {
				wrong.add(path + ": expected " + want + ", got " + got);
			}
		});
		Set<String> wantMissing = new TreeSet<>((List<String>) sample.getOrDefault("missing", List.of()));
		Set<String> gotMissing = new RequiredFields().missing(ontology, output.projection(), record).stream()
				.map(RequiredFields.Missing::path).collect(Collectors.toCollection(TreeSet::new));
		if (!wantMissing.equals(gotMissing)) {
			wrong.add("missing: expected " + wantMissing + ", got " + gotMissing);
		}
		assertThat(wrong).as("%s\nrecord: %s", file.getFileName(), record).isEmpty();
	}

	/** Every non-null value at {@code path}, going through lists. */
	private static List<Object> values(Object root, String path) {
		List<Object> current = new ArrayList<>(List.of(root));
		for (String key : path.split("\\.")) {
			List<Object> next = new ArrayList<>();
			for (Object c : current) {
				Object v = c instanceof Map<?, ?> m ? m.get(key) : null;
				if (v instanceof List<?> l) {
					next.addAll(l);
				}
				else if (v != null) {
					next.add(v);
				}
			}
			current = next;
		}
		current.removeIf(java.util.Objects::isNull);
		return current;
	}

	/** Numbers compare by value (100 = 100.0), everything else by text. */
	private static String text(Object value) {
		return value instanceof Number n ? new BigDecimal(n.toString()).stripTrailingZeros().toPlainString()
				: String.valueOf(value);
	}
}

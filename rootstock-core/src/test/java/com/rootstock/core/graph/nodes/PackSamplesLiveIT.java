package com.rootstock.core.graph.nodes;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.cases.CaseGraphs;
import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.Lookup;
import com.rootstock.core.graph.GraphDefinition;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeRegistry;
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
 * Runs the agents of the configured packs on their sample inputs
 * ({@code <pack>/samples/<agent>/*.yaml}) against the real model, prompt and
 * client system, and checks what they produce. Tagged {@code live}: each
 * sample makes Bedrock calls ({@code mvn verify -Dlive.excluded=none -Dgroups=live}).
 *
 * <p>A structured-extraction sample has {@code input} (the text submitted),
 * {@code expect} (field path to value; paths go through lists, a list value
 * means exactly that set of values) and {@code missing} (exactly the required
 * fields the input lacks).
 *
 * <p>A tool-calling sample has {@code input} and {@code record} (the state it
 * starts from) and {@code expect}: {@code ok} (tools with a successful lookup),
 * {@code notOk} (tools without one) and {@code arguments} ({@code tool.argument}
 * to the value or values its successful lookups used). It needs the client
 * system running.
 *
 * <p>A judge sample has {@code input} and {@code record}, and {@code expect}:
 * {@code flagged} (record paths it must report: an issue whose path starts with
 * each) or {@code clean: true} (it must report nothing).
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
	NodeRegistry registry;

	@TestFactory
	Stream<DynamicTest> everySampleIsExtractedAsExpected() throws IOException {
		List<DynamicTest> tests = new ArrayList<>();
		for (CaseGraph graph : graphs.all()) {
			PackGraph definition = graph.definition();
			LoadedPack pack = packs.snapshot().packs().stream().filter(p -> p.name().equals(graph.pack())).findFirst()
					.orElseThrow();
			ResolvedOntology ontology = packs.ontology(pack.name()).orElseThrow();
			for (AgentDefinition agent : definition.agents().values()) {
				if (!List.of(StructuredExtraction.TYPE, ToolCallingAgent.TYPE, JudgeAgent.TYPE)
						.contains(agent.spec().type())) {
					continue;
				}
				// The node that runs it: its own, or one whose config names it (a judge, for validate).
				GraphDefinition.NodeSpec node = definition.graph().nodes().stream()
						.filter(n -> agent.name().equals(n.agent()) || n.config().containsValue(agent.name()))
						.findFirst().orElse(null);
				if (node == null) {
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
								() -> {
									if (agent.spec().type().equals(ToolCallingAgent.TYPE)) {
										checkLookups(context, file);
									}
									else if (agent.spec().type().equals(JudgeAgent.TYPE)) {
										checkJudge(context, file);
									}
									else {
										check(context, ontology, file);
									}
								}));
					}
				}
			}
		}
		return tests.stream();
	}

	@SuppressWarnings("unchecked")
	private void check(NodeContext context, ResolvedOntology ontology, Path file) throws Exception {
		Map<String, Object> sample = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
		Map<String, Object> update = registry.agent(StructuredExtraction.TYPE).orElseThrow().create(context)
				.apply(new CaseState(Map.of(CaseState.CASE_ID, "sample", CaseState.RAW_INPUT, sample.get("input"))));
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

	@SuppressWarnings("unchecked")
	private void checkLookups(NodeContext context, Path file) throws Exception {
		Map<String, Object> sample = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
		Map<String, Object> update = registry.agent(ToolCallingAgent.TYPE).orElseThrow().create(context)
				.apply(new CaseState(Map.of(CaseState.CASE_ID, "sample", CaseState.RAW_INPUT, sample.get("input"),
						"record", sample.get("record"))));
		Map<String, Object> output = (Map<String, Object>) update.get(context.agent().spec().output().writeTo());
		List<Lookup> lookups = (List<Lookup>) output.get("lookups");
		List<Lookup> ok = lookups.stream().filter(l -> l.status().equals("OK")).toList();
		Set<String> okTools = ok.stream().map(Lookup::tool).collect(Collectors.toSet());
		Map<String, Object> expect = (Map<String, Object>) sample.getOrDefault("expect", Map.of());

		List<String> wrong = new ArrayList<>();
		((List<String>) expect.getOrDefault("ok", List.of())).stream().filter(t -> !okTools.contains(t))
				.forEach(t -> wrong.add("no successful " + t));
		((List<String>) expect.getOrDefault("notOk", List.of())).stream().filter(okTools::contains)
				.forEach(t -> wrong.add("unexpected successful " + t));
		((Map<String, Object>) expect.getOrDefault("arguments", Map.of())).forEach((key, expected) -> {
			String[] parts = key.split("\\.", 2);
			Set<String> want = (expected instanceof List<?> l ? l.stream() : Stream.of(expected)).map(PackSamplesLiveIT::text)
					.collect(Collectors.toCollection(TreeSet::new));
			Set<String> got = ok.stream().filter(l -> l.tool().equals(parts[0]))
					.map(l -> text(l.arguments().get(parts[1]))).collect(Collectors.toCollection(TreeSet::new));
			if (!want.equals(got)) {
				wrong.add(key + ": expected " + want + ", got " + got);
			}
		});
		assertThat(wrong).as("%s\nsummary: %s\nlookups: %s", file.getFileName(), output.get("summary"), lookups.stream()
				.map(l -> l.source() + " " + l.tool() + " " + l.arguments() + " " + l.status()
						+ (l.message() == null ? "" : " (" + l.message() + ")"))
				.toList()).isEmpty();
	}

	@SuppressWarnings("unchecked")
	private void checkJudge(NodeContext context, Path file) throws Exception {
		Map<String, Object> sample = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
		Map<String, Object> update = registry.agent(JudgeAgent.TYPE).orElseThrow().create(context)
				.apply(new CaseState(Map.of(CaseState.CASE_ID, "sample", CaseState.RAW_INPUT, sample.get("input"),
						"record", sample.get("record"))));
		List<CaseIssue> issues = (List<CaseIssue>) update.get(context.agent().spec().output().writeTo());
		Map<String, Object> expect = (Map<String, Object>) sample.getOrDefault("expect", Map.of());

		List<String> wrong = new ArrayList<>();
		if (Boolean.TRUE.equals(expect.get("clean")) && !issues.isEmpty()) {
			wrong.add("expected nothing flagged");
		}
		for (String path : (List<String>) expect.getOrDefault("flagged", List.of())) {
			if (issues.stream().noneMatch(i -> i.path() != null && i.path().startsWith(path))) {
				wrong.add("nothing flagged at " + path);
			}
		}
		assertThat(wrong).as("%s\nissues: %s", file.getFileName(), issues.stream()
				.map(i -> i.path() + ": " + i.message()).toList()).isEmpty();
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

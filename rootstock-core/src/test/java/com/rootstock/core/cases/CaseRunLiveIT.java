package com.rootstock.core.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.Lookup;
import com.rootstock.core.graph.nodes.ToolCallingAgent;
import com.rootstock.core.pack.PackRegistry;
import com.rootstock.testsupport.ThrowawaySchemaConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Whole cases through each configured pack's graph, with the real model,
 * prompts and client system. Each file in {@code <pack>/samples/cases/} has the
 * {@code input} submitted and what the run must show ({@code expect.issues}:
 * rule ids among its issues; {@code expect.pausedAt}: where it stops); the
 * first sample of the pack's extraction agent runs too, and must simply stop
 * where a person is needed. Every run must have a record and, for every
 * tool-calling agent, at least one successful lookup. Tagged {@code live};
 * needs the client system running.
 */
@Tag("live")
@SpringBootTest
@Import(ThrowawaySchemaConfig.class)
class CaseRunLiveIT {

	@Autowired
	CaseGraphs graphs;

	@Autowired
	CaseRunService service;

	@Autowired
	PackRegistry packs;

	@TestFactory
	Stream<DynamicTest> sampleCasesRunThroughTheWholeGraph() throws Exception {
		List<DynamicTest> tests = new ArrayList<>();
		for (CaseGraph graph : graphs.all()) {
			Path samples = Path.of(packs.snapshot().packs().stream().filter(p -> p.name().equals(graph.pack()))
					.findFirst().orElseThrow().location(), "samples");
			List<Path> files = new ArrayList<>(yaml(samples.resolve("cases")));
			graph.definition().agents().values().stream().filter(a -> a.spec().type().equals("structured-extraction"))
					.findFirst().flatMap(a -> yaml(samples.resolve(a.name())).stream().findFirst()).ifPresent(files::add);
			for (Path file : files) {
				tests.add(DynamicTest.dynamicTest(graph.pack() + " " + samples.relativize(file), () -> run(graph, file)));
			}
		}
		return tests.stream();
	}

	private static List<Path> yaml(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList();
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private void run(CaseGraph graph, Path sample) throws Exception {
		Map<String, Object> s = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(sample));
		CaseRun run = service.start(graph.pack(), (String) s.get("input"), Map.of(), "live-test");
		for (int i = 0; i < 600 && run.status() == CaseRun.Status.RUNNING; i++) {
			Thread.sleep(500);
		}

		List<CaseIssue> issues = run.result().get("issues") instanceof List<?> l
				? l.stream().map(CaseIssue.class::cast).toList() : List.of();
		String story = "\n" + sample.getFileName() + ": " + run.status() + " " + run.pause() + " " + run.error()
				+ "\nissues: " + issues.stream().map(i -> i.ruleId() + " " + i.path() + ": " + i.message()).toList()
				+ "\nrecord: " + run.result().get("record");
		assertThat(run.status()).as(story).isIn(CaseRun.Status.PAUSED, CaseRun.Status.COMPLETED);
		assertThat(run.result().get("record")).as(story).isInstanceOf(Map.class);
		for (var agent : graph.definition().agents().values()) {
			if (agent.spec().type().equals(ToolCallingAgent.TYPE)) {
				Map<String, Object> context = (Map<String, Object>) run.result().get(agent.spec().output().writeTo());
				List<Lookup> lookups = (List<Lookup>) context.get("lookups");
				assertThat(lookups).as("%s lookups%s", agent.name(), story).anyMatch(Lookup::ok);
			}
		}
		Map<String, Object> expect = (Map<String, Object>) s.getOrDefault("expect", Map.of());
		assertThat(issues).as(story).extracting(CaseIssue::ruleId)
				.containsAll((List<String>) expect.getOrDefault("issues", List.of()));
		if (expect.get("pausedAt") != null) {
			assertThat(run.pause()).as(story).isNotNull().extracting(CaseRun.Pause::node).isEqualTo(expect.get("pausedAt"));
		}
	}
}

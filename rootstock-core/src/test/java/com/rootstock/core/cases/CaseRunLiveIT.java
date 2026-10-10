package com.rootstock.core.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.CaseGraph;
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
 * A whole case through each configured pack's graph, with the real model,
 * prompts and client system: the first sample of the pack's first extraction
 * agent goes in, and the run must stop where a person is needed (not fail or
 * be parked), with a record extracted and every tool-calling agent's lookups
 * at least partly successful. Tagged {@code live}; needs the client system running.
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
	Stream<DynamicTest> aSampleCaseRunsThroughTheWholeGraph() throws Exception {
		List<DynamicTest> tests = new ArrayList<>();
		for (CaseGraph graph : graphs.all()) {
			String location = packs.snapshot().packs().stream().filter(p -> p.name().equals(graph.pack())).findFirst()
					.orElseThrow().location();
			Path samples = Path.of(location, "samples");
			String extractor = graph.definition().agents().values().stream()
					.filter(a -> a.spec().type().equals("structured-extraction")).map(a -> a.name()).findFirst().orElse(null);
			if (extractor == null || !Files.isDirectory(samples.resolve(extractor))) {
				continue;
			}
			Path first;
			try (Stream<Path> files = Files.list(samples.resolve(extractor))) {
				first = files.filter(f -> f.toString().endsWith(".yaml")).sorted().findFirst().orElse(null);
			}
			if (first == null) {
				continue;
			}
			tests.add(DynamicTest.dynamicTest(graph.pack() + " " + first.getFileName(), () -> run(graph, first)));
		}
		return tests.stream();
	}

	@SuppressWarnings("unchecked")
	private void run(CaseGraph graph, Path sample) throws Exception {
		Map<String, Object> s = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(sample));
		CaseRun run = service.start(graph.pack(), (String) s.get("input"), Map.of(), "live-test");
		for (int i = 0; i < 600 && run.status() == CaseRun.Status.RUNNING; i++) {
			Thread.sleep(500);
		}

		assertThat(run.status()).as("%s: %s", run.error(), run.events()).isIn(CaseRun.Status.PAUSED, CaseRun.Status.COMPLETED);
		assertThat(run.result().get("record")).as("record").isInstanceOf(Map.class);
		for (var agent : graph.definition().agents().values()) {
			if (!agent.spec().type().equals(ToolCallingAgent.TYPE)) {
				continue;
			}
			Map<String, Object> context = (Map<String, Object>) run.result().get(agent.spec().output().writeTo());
			List<ToolCallingAgent.Lookup> lookups = (List<ToolCallingAgent.Lookup>) context.get("lookups");
			assertThat(lookups).as("%s lookups: %s", agent.name(), lookups).anyMatch(l -> l.status().equals("OK"));
		}
	}
}

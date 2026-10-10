package com.rootstock.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackGraphLoaderTest {

	@TempDir
	Path tmp;

	private GraphDefinitionException failure(String file, UnaryOperator<String> edit) {
		Path dir = RepairsPack.copyWith(tmp.resolve("repairs"), file, edit);
		try {
			new PackGraphLoader().load("repairs", dir);
		}
		catch (GraphDefinitionException e) {
			return e;
		}
		throw new AssertionError("expected the pack to be rejected");
	}

	@Test
	void theGraphAndAgentsLoad() {
		PackGraph pack = RepairsPack.load();

		GraphDefinition g = pack.graph();
		assertThat(g.metadata().name()).isEqualTo("job-intake");
		assertThat(g.nodes()).extracting(GraphDefinition.NodeSpec::id).containsExactly("ingest", "extract", "enrich",
				"validate", "clarify", "draft", "chase", "review", "commit", "await_input");
		assertThat(g.state().channels().get("issues").reducer()).isEqualTo(GraphDefinition.Reducer.REPLACE);
		assertThat(g.state().channels().get("actions").reducer()).isEqualTo(GraphDefinition.Reducer.APPEND);
		assertThat(g.edges().get(4).route()).hasSize(3);
		assertThat(g.edges().get(4).route().get(2).isDefault()).isTrue();
		assertThat(g.runtime().interruptBefore()).containsExactly("review", "await_input");
		assertThat(g.runtime().maxSteps()).isEqualTo(40);
		assertThat(pack.agents()).containsOnlyKeys("job-extractor", "property-lookup", "job-judge", "question-writer",
				"visit-planner", "tenant-chaser");
		assertThat(pack.agents().get("job-extractor").spec().output().projection()).isEqualTo("job-intake");
		assertThat(pack.agents().get("property-lookup").spec().tools().allow()).containsExactly("find_property",
				"list_contractors");
	}

	@Test
	void aTypoIsReportedByTheSchemaWithItsLocation() {
		GraphDefinitionException e = failure("graph.yaml", s -> s.replace("{ id: ingest,  type: ingest }",
				"{ id: ingest,  typ: ingest }"));

		assertThat(e.problems()).anySatisfy(p -> {
			assertThat(p.file()).isEqualTo("graph.yaml");
			assertThat(p.at()).isEqualTo("nodes[0]");
			assertThat(p.message()).contains("typ");
		});
	}

	@Test
	void anUnknownReducerIsRejected() {
		GraphDefinitionException e = failure("graph.yaml", s -> s.replace("audit:     { reducer: append",
				"audit:     { reducer: concat"));

		assertThat(e.problems()).anySatisfy(p -> assertThat(p.at()).isEqualTo("state.channels.audit.reducer"));
	}

	@Test
	void problemsInSeveralFilesAreReportedTogether() {
		Path dir = RepairsPack.copyWith(tmp.resolve("repairs"), "agents/job-judge.yaml",
				s -> s.replace("name: job-judge", "name: the-judge"));
		RepairsPack.copyWith(tmp.resolve("other"), "graph.yaml", s -> s);
		java.nio.file.Files.exists(dir);
		Path both = RepairsPack.copyWith(tmp.resolve("both"), "agents/tenant-chaser.yaml",
				s -> s.replace("writeTo: actions", "writeTo: actions, colour: red"));
		try {
			java.nio.file.Files.writeString(both.resolve("agents/job-judge.yaml"),
					java.nio.file.Files.readString(dir.resolve("agents/job-judge.yaml")));
		}
		catch (java.io.IOException ex) {
			throw new AssertionError(ex);
		}

		assertThatThrownBy(() -> new PackGraphLoader().load("repairs", both)).isInstanceOfSatisfying(
				GraphDefinitionException.class, e -> assertThat(e.problems()).extracting(GraphProblem::file)
						.contains("agents/job-judge.yaml", "agents/tenant-chaser.yaml"));
	}

	@Test
	void invalidYamlNamesTheLine() {
		GraphDefinitionException e = failure("graph.yaml", s -> s + "\n  bad: [\n");

		assertThat(e.problems().get(0).at()).startsWith("line ");
		assertThat(e.problems().get(0).message()).startsWith("not valid YAML");
	}

	@Test
	void theApiVersionIsChecked() {
		GraphDefinitionException e = failure("graph.yaml", s -> s.replace("apiVersion: rootstock/v1", "apiVersion: rootstock/v9"));

		assertThat(e.problems()).anySatisfy(p -> assertThat(p.at()).isEqualTo("apiVersion"));
	}
}

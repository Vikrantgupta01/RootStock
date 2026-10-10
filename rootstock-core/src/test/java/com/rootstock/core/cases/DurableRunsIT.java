package com.rootstock.core.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.graph.CaseGraph;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.GraphCompiler;
import com.rootstock.core.graph.JdbcCheckpointSaver;
import com.rootstock.core.graph.PackGraph;
import com.rootstock.core.graph.PackGraphLoader;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.testsupport.ThrowawaySchemaConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Paused cases survive a restart: a run checkpointed in Postgres pauses before
 * review, everything in memory is thrown away (a new store, registry, saver,
 * compiled graph and service, as after a restart), and the run is found again
 * and resumed to the end. On a throwaway RDS schema; the stub nodes, so no model.
 */
@SpringBootTest
@Import(ThrowawaySchemaConfig.class)
class DurableRunsIT {

	@TempDir
	Path tmp;

	@Autowired
	JdbcTemplate jdbc;

	/** Rootstock as it is after a (re)start: nothing in memory, everything from the database. */
	private record Instance(RunRegistry runs, CaseRunService service) {
	}

	private Instance start(PackGraph pack) {
		RunRegistry runs = new RunRegistry(List.of(), new JdbcRunStore(jdbc, JsonMapper.builder().build()));
		CaseGraph graph = new GraphCompiler(RepairsPack.registry(), List.of(), runs, new JdbcCheckpointSaver(jdbc))
				.compile(pack, RepairsPack.ontology());
		return new Instance(runs, new CaseRunService(new CaseGraphs(List.of(graph)), runs));
	}

	private static CaseRun settled(CaseRun run) throws InterruptedException {
		for (int i = 0; i < 400 && run.status() == CaseRun.Status.RUNNING; i++) {
			Thread.sleep(25);
		}
		return run;
	}

	@Test
	void aPausedCaseIsResumedAfterARestart() throws Exception {
		PackGraph pack = new PackGraphLoader().load("repairs", RepairsPack.copyWith(tmp.resolve("repairs"),
				"graph.yaml", s -> s.replace("checkpointer: memory", "checkpointer: postgres")));

		Instance before = start(pack);
		CaseRun paused = settled(before.service().start(null, "Kitchen tap leaking", Map.of(), "member-1"));
		assertThat(paused.pause()).isEqualTo(new CaseRun.Pause("review", true));
		before.service().close();

		Instance after = start(pack);
		CaseRun restored = after.runs().byCase(paused.caseId()).orElseThrow();
		assertThat(restored.status()).isEqualTo(CaseRun.Status.PAUSED);
		assertThat(restored.input()).isEqualTo("Kitchen tap leaking");
		assertThat(restored.events()).isEqualTo(paused.events());
		assertThat(after.runs().paused()).extracting(CaseRun::caseId).contains(paused.caseId());

		after.service().resume(paused.caseId(), Map.of(CaseState.REVIEW, Map.of("decision", "APPROVED", "by", "m-2")),
				"APPROVED by m-2");
		settled(restored);

		assertThat(restored.status()).isEqualTo(CaseRun.Status.COMPLETED);
		assertThat(restored.events()).filteredOn(e -> e.type() == RunEvent.Type.NODE_FINISHED).extracting(RunEvent::node)
				.containsExactly("ingest", "extract", "enrich", "validate", "draft", "review", "commit");
		// The record extracted before the restart came back from the checkpoint.
		assertThat(restored.result()).containsKey("record");
		assertThat(start(pack).runs().byCase(paused.caseId()).orElseThrow().status())
				.isEqualTo(CaseRun.Status.COMPLETED);
		after.service().close();
	}

	@Test
	void aRunCutOffByAStopIsMarkedFailedNotLeftRunning() {
		CaseRun run = new CaseRun("case-x", "run-x", "repairs", "job-intake", "1.0.0", "member-1", "Tap");
		new JdbcRunStore(jdbc, JsonMapper.builder().build()).save(run);

		RunRegistry runs = new RunRegistry(List.of(), new JdbcRunStore(jdbc, JsonMapper.builder().build()));
		assertThat(runs.failInterrupted()).isEqualTo(1);

		CaseRun saved = runs.byCase("case-x").orElseThrow();
		assertThat(saved.status()).isEqualTo(CaseRun.Status.FAILED);
		assertThat(saved.error()).contains("Rootstock stopped while this run was in progress");
	}
}

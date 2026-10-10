package com.rootstock.core.cases;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs in Postgres ({@code case_run}, created by Flyway). A case may have several
 * runs; lookups by case give its latest.
 */
public final class JdbcRunStore implements RunStore {

	private static final String COLUMNS = """
			run_id, case_id, pack, graph, graph_version, started_by, input, status, pause_node, pause_before, error,
			trace_id, started_at, events, result""";

	private final JdbcTemplate jdbc;
	private final JsonMapper json;

	public JdbcRunStore(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	public void save(CaseRun run) {
		CaseRun.Pause pause = run.pause();
		jdbc.update("""
				INSERT INTO case_run (run_id, case_id, pack, graph, graph_version, started_by, input, status,
				                      pause_node, pause_before, error, trace_id, started_at, updated_at, events, result)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?::jsonb, ?::jsonb)
				ON CONFLICT (run_id) DO UPDATE SET status = EXCLUDED.status, pause_node = EXCLUDED.pause_node,
				    pause_before = EXCLUDED.pause_before, error = EXCLUDED.error, trace_id = EXCLUDED.trace_id,
				    updated_at = now(), events = EXCLUDED.events, result = EXCLUDED.result""",
				run.runId(), run.caseId(), run.pack(), run.graph(), run.graphVersion(), run.startedBy(), run.input(),
				run.status().name(), pause == null ? null : pause.node(), pause == null ? null : pause.before(),
				run.error(), run.traceId(), Timestamp.from(run.startedAt()), json.writeValueAsString(run.events()),
				json.writeValueAsString(run.result()));
	}

	@Override
	public Optional<CaseRun> byCase(String caseId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM case_run WHERE case_id = ? ORDER BY started_at DESC LIMIT 1",
				this::run, caseId).stream().findFirst();
	}

	@Override
	public List<CaseRun> recent(int limit) {
		return jdbc.query("SELECT * FROM (SELECT DISTINCT ON (case_id) " + COLUMNS + " FROM case_run "
				+ "ORDER BY case_id, started_at DESC) latest ORDER BY started_at DESC LIMIT ?", this::run, limit);
	}

	@Override
	public List<CaseRun> running() {
		return jdbc.query("SELECT " + COLUMNS + " FROM case_run WHERE status = 'RUNNING'", this::run);
	}

	@Override
	public List<CaseRun> paused() {
		return jdbc.query("SELECT * FROM (SELECT DISTINCT ON (case_id) " + COLUMNS + " FROM case_run "
				+ "ORDER BY case_id, started_at DESC) latest WHERE status = 'PAUSED' ORDER BY started_at", this::run);
	}

	private CaseRun run(ResultSet rs, int n) throws SQLException {
		String pauseNode = rs.getString("pause_node");
		return CaseRun.restore(rs.getString("case_id"), rs.getString("run_id"), rs.getString("pack"),
				rs.getString("graph"), rs.getString("graph_version"), rs.getString("started_by"), rs.getString("input"),
				rs.getTimestamp("started_at").toInstant(), CaseRun.Status.valueOf(rs.getString("status")),
				pauseNode == null ? null : new CaseRun.Pause(pauseNode, rs.getBoolean("pause_before")),
				rs.getString("error"), rs.getString("trace_id"),
				json.readValue(rs.getString("events"), new TypeReference<List<RunEvent>>() {
				}),
				json.readValue(rs.getString("result"), new TypeReference<Map<String, Object>>() {
				}));
	}
}

package com.rootstock.core.cases;

import com.rootstock.core.graph.JdbcCheckpointSaver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Case files in Postgres ({@code case_file}, created by Flyway). */
public final class JdbcCaseStore implements CaseStore {

	private static final String COLUMNS = """
			case_id, pack, status, next_graph, waiting_for, started_by, input, created_at, updated_at, last_run_id, state""";

	private final JdbcTemplate jdbc;

	public JdbcCaseStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void save(CaseFile f) {
		jdbc.update("""
				INSERT INTO case_file (case_id, pack, status, next_graph, waiting_for, started_by, input, created_at,
				                       updated_at, last_run_id, state)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (case_id) DO UPDATE SET status = EXCLUDED.status, next_graph = EXCLUDED.next_graph,
				    waiting_for = EXCLUDED.waiting_for, updated_at = EXCLUDED.updated_at,
				    last_run_id = EXCLUDED.last_run_id, state = EXCLUDED.state""",
				f.caseId(), f.pack(), f.status(), f.next(), String.join(",", f.waitingFor()), f.startedBy(), f.input(),
				Timestamp.from(f.createdAt()), Timestamp.from(f.updatedAt()), f.lastRunId(),
				JdbcCheckpointSaver.write(f.state()));
	}

	@Override
	public Optional<CaseFile> byId(String caseId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM case_file WHERE case_id = ?", this::file, caseId).stream()
				.findFirst();
	}

	@Override
	public List<CaseFile> waiting() {
		return jdbc.query("SELECT " + COLUMNS + " FROM case_file WHERE next_graph IS NOT NULL AND status <> 'RUNNING' "
				+ "ORDER BY updated_at", this::file);
	}

	private CaseFile file(ResultSet rs, int n) throws SQLException {
		String waiting = rs.getString("waiting_for");
		byte[] state = rs.getBytes("state");
		return new CaseFile(rs.getString("case_id"), rs.getString("pack"), rs.getString("status"),
				rs.getString("next_graph"),
				waiting == null ? List.of() : Arrays.stream(waiting.split(",")).filter(s -> !s.isBlank()).toList(),
				rs.getString("started_by"), rs.getString("input"), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant(), rs.getString("last_run_id"),
				state == null ? null : JdbcCheckpointSaver.read(state));
	}
}

package com.rootstock.core.graph;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LangGraph4j checkpoints in Postgres ({@code case_checkpoint}, created by
 * Flyway), so a paused run survives a restart: a case can wait days for a
 * coordinator without holding any memory. Graphs choose it with
 * {@code runtime.checkpointer: postgres}.
 *
 * <p>State is Java-serialized: everything a node writes into state is
 * {@link java.io.Serializable}, the same rule the in-memory checkpointer relies
 * on. Each thread (one run) is a list of checkpoints, newest first, exactly as
 * the in-memory one keeps them; releasing a thread deletes it.
 *
 * <p>Reading state back only accepts the JDK's and Rootstock's own classes, so
 * a tampered row cannot instantiate anything else.
 */
public final class JdbcCheckpointSaver extends AbstractCheckpointSaver {

	private static final ObjectInputFilter ONLY_OWN_CLASSES = ObjectInputFilter.Config
			.createFilter("java.**;com.rootstock.**;!*");

	private final JdbcTemplate jdbc;

	public JdbcCheckpointSaver(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
		List<Checkpoint> rows = jdbc.query("""
				SELECT checkpoint_id, node_id, next_node_id, state FROM case_checkpoint
				WHERE thread_id = ? ORDER BY seq DESC""",
				(rs, n) -> Checkpoint.builder().id(rs.getString(1)).nodeId(rs.getString(2))
						.nextNodeId(rs.getString(3)).state(read(rs.getBytes(4))).build(),
				threadId(config));
		return new LinkedList<>(rows);
	}

	@Override
	protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
		jdbc.update("""
				INSERT INTO case_checkpoint (thread_id, checkpoint_id, node_id, next_node_id, state)
				VALUES (?, ?, ?, ?, ?)""",
				threadId(config), checkpoint.id(), checkpoint.nodeId(), checkpoint.nextNodeId(),
				write(checkpoint.state()));
	}

	/** The checkpoint named in the config was replaced (e.g. by updateState before a resume). */
	@Override
	protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
		String replaced = config.checkPointId().orElseThrow();
		int updated = jdbc.update("""
				UPDATE case_checkpoint SET checkpoint_id = ?, node_id = ?, next_node_id = ?, state = ?, saved_at = now()
				WHERE thread_id = ? AND checkpoint_id = ?""",
				checkpoint.id(), checkpoint.nodeId(), checkpoint.nextNodeId(), write(checkpoint.state()),
				threadId(config), replaced);
		if (updated != 1) {
			throw new IllegalStateException("No checkpoint '" + replaced + "' in thread " + threadId(config));
		}
	}

	@Override
	protected Tag releaseCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints, String message) {
		jdbc.update("DELETE FROM case_checkpoint WHERE thread_id = ?", threadId(config));
		return new Tag(threadId(config), 1, checkpoints);
	}

	@Override
	protected Tag releaseCheckpointsOnError(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Throwable error) {
		return releaseCheckpoints(config, checkpoints, error.getMessage());
	}

	@Override
	public <State extends AgentState> CompletableFuture<InterruptionMetadata<State>> registerInterruption(
			RunnableConfig config, InterruptionMetadata<State> metadata) {
		return CompletableFuture.completedFuture(metadata);
	}

	@Override
	public Optional<Tag> tag(RunnableConfig config, Integer version) {
		return Optional.empty();
	}

	public static byte[] write(Map<String, Object> state) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(new HashMap<>(state));
		}
		catch (IOException e) {
			throw new IllegalStateException("A case's state could not be saved; everything a node writes into state "
					+ "must be Serializable: " + e.getMessage(), e);
		}
		return bytes.toByteArray();
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> read(byte[] data) {
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
			in.setObjectInputFilter(ONLY_OWN_CLASSES);
			return (Map<String, Object>) in.readObject();
		}
		catch (IOException | ClassNotFoundException e) {
			throw new IllegalStateException("A saved case state could not be read: " + e.getMessage(), e);
		}
	}
}

package com.rootstock.core.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;

/**
 * The state of one case as it moves through a graph. The channels a pack
 * declares in {@code graph.yaml} (record, context, issues, actions, audit…)
 * live here alongside a few the engine itself sets.
 *
 * <p>Everything a node puts in the state must be {@link java.io.Serializable}:
 * the checkpointer copies state by serializing it, and will store it in
 * Postgres once paused cases persist.
 */
public class CaseState extends AgentState {

	/** Set by the engine when a run starts; a node never changes them. */
	public static final String CASE_ID = "caseId";
	public static final String RUN_ID = "runId";
	public static final String PACK = "pack";
	/** The input as submitted, e.g. a member's visit notes. */
	public static final String RAW_INPUT = "rawInput";
	/** Run options, e.g. the stub nodes' {@code simulate}. */
	public static final String OPTIONS = "options";
	/** How many clarify rounds the case has been through. */
	public static final String CLARIFY_ROUNDS = "clarifyRounds";
	/** A reviewer's decision, written when a paused run is resumed. */
	public static final String REVIEW = "review";

	/** Keys a graph may read or route on without declaring them. */
	public static final List<String> ENGINE_KEYS = List.of(CASE_ID, RUN_ID, PACK, RAW_INPUT, OPTIONS, CLARIFY_ROUNDS,
			REVIEW);

	public CaseState(Map<String, Object> data) {
		super(data);
	}

	public String caseId() {
		return this.<String>value(CASE_ID).orElseThrow();
	}

	public String runId() {
		return this.<String>value(RUN_ID).orElseThrow();
	}

	public Optional<String> rawInput() {
		return value(RAW_INPUT);
	}

	public Map<String, Object> options() {
		return this.<Map<String, Object>>value(OPTIONS).orElse(Map.of());
	}

	public int clarifyRounds() {
		return this.<Integer>value(CLARIFY_ROUNDS).orElse(0);
	}

	public <T> List<T> list(String channel) {
		return this.<List<T>>value(channel).orElse(List.of());
	}
}

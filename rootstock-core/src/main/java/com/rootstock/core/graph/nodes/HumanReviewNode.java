package com.rootstock.core.graph.nodes;

import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import java.util.Map;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * The {@code human-review} node. A run pauses before it (the graph's
 * {@code interruptBefore}) and a review task waits for a person whose role is in
 * the node's {@code approverRoles}; their decision is written into the state as
 * {@code review} when the run resumes, and the edges route on it. By the time
 * this node runs, then, the decision is made: it records it in the audit trail.
 * Reaching it without one means the graph lets a run past review undecided,
 * which must never happen: the run fails.
 */
public final class HumanReviewNode implements NodeFactory {

	public static final String TYPE = "human-review";

	@Override
	public Kind kind() {
		return Kind.NODE;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public NodeAction<CaseState> create(NodeContext context) {
		return state -> {
			Map<?, ?> review = state.<Map<?, ?>>value(CaseState.REVIEW).orElse(null);
			if (review == null || review.get("decision") == null) {
				throw new IllegalStateException("Node '" + context.node().id() + "' was reached without a review "
						+ "decision; it must be paused before (interruptBefore) and resumed by a reviewer");
			}
			Object by = review.get("byEmail") != null ? review.get("byEmail") : review.get("by");
			Object comment = review.get("comment");
			return AuditEntry.update(context.node().id(), review.get("decision") + " by " + by
					+ (comment == null || String.valueOf(comment).isBlank() ? "" : ": " + comment));
		};
	}
}

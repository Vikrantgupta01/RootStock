package com.rootstock.core.graph.stub;

import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.graph.ProposedAction;
import com.rootstock.core.ontology.JsonSchemaGenerator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * Stand-ins for every node and agent type, so a whole graph runs before any of
 * them does real work (Iteration 5). Each one does something small and
 * visible, records it in the audit channel, and never calls a model or a client
 * system. Real implementations replace them type by type in later iterations.
 *
 * <p>Two run options drive the routes while there is no real validation:
 * {@code simulate: clarify} makes the rules node raise a BLOCKING issue the
 * submitter can answer (first pass only), {@code simulate: chase} one only an
 * external party can answer.
 */
public final class StubNodes {

	public static final String SIMULATE = "simulate";

	private StubNodes() {
	}

	/**
	 * @param pause how long each stub takes, so progress is visible on screen; zero in tests
	 */
	public static List<NodeFactory> all(Duration pause) {
		return List.of(
				node("ingest", pause, StubNodes::ingest),
				node("rules", pause, StubNodes::rules),
				node("clarify", pause, StubNodes::clarify),
				node("human-review", pause, (ctx, s) -> audit(ctx, "Stub: would record the reviewer's decision")),
				node("tool-executor", pause, (ctx, s) -> audit(ctx, "Stub: would run "
						+ s.list("actions").size() + " approved action(s); calls no tools")),
				node("await-input", pause, (ctx, s) -> audit(ctx, "Stub: would wait for new information")),
				agent("structured-extraction", pause, StubNodes::extraction),
				agent("tool-calling", pause, StubNodes::toolCalling),
				agent("judge", pause, (ctx, s) -> audit(ctx, "Stub: the judge found nothing")),
				agent("drafter", pause, StubNodes::drafter));
	}

	private static Map<String, Object> ingest(NodeContext ctx, CaseState state) {
		String notes = state.rawInput().orElse("").strip();
		int words = notes.isEmpty() ? 0 : notes.split("\\s+").length;
		Map<String, Object> update = audit(ctx, "Received " + words + " words of input");
		update.put(CaseState.RAW_INPUT, notes);
		return update;
	}

	/** An empty record shaped like the agent's projection: the fields the real extractor will fill. */
	private static Map<String, Object> extraction(NodeContext ctx, CaseState state) {
		AgentDefinition.Output output = ctx.agent().spec().output();
		Map<String, Object> record = new LinkedHashMap<>();
		if (output.projection() != null && ctx.ontology() != null) {
			@SuppressWarnings("unchecked")
			Map<String, Object> properties = (Map<String, Object>) new JsonSchemaGenerator()
					.generate(ctx.ontology(), output.projection()).get("properties");
			properties.keySet().forEach(field -> record.put(field, null));
		}
		Map<String, Object> update = audit(ctx, "Stub: would extract " + record.size() + " fields of projection '"
				+ output.projection() + "' from the input");
		update.put(output.writeTo(), record);
		return update;
	}

	private static Map<String, Object> toolCalling(NodeContext ctx, CaseState state) {
		AgentDefinition.Spec spec = ctx.agent().spec();
		List<String> tools = spec.tools() == null ? List.of() : spec.tools().allow();
		Map<String, Object> update = audit(ctx, "Stub: would look things up with " + tools + "; calls no tools");
		update.put(spec.output().writeTo(), Map.of("lookedUpWith", tools));
		return update;
	}

	private static Map<String, Object> rules(NodeContext ctx, CaseState state) {
		Object simulate = state.options().get(SIMULATE);
		if ("clarify".equals(simulate) && state.clarifyRounds() == 0) {
			Map<String, Object> update = audit(ctx, "Stub: simulated a missing detail the submitter can give");
			update.put("issues", List.of(new CaseIssue("STUB-missing-detail", CaseIssue.BLOCKING, CaseIssue.SUBMITTER,
					"Simulated: the visit date is missing")));
			return update;
		}
		if ("chase".equals(simulate)) {
			Map<String, Object> update = audit(ctx, "Stub: simulated a detail only the household can give");
			update.put("issues", List.of(new CaseIssue("STUB-needs-household", CaseIssue.BLOCKING, CaseIssue.EXTERNAL,
					"Simulated: the household's consent is not recorded")));
			return update;
		}
		// Always the whole list: each validation pass replaces the last one's issues.
		Map<String, Object> update = audit(ctx, "Stub: no rules run yet; no issues");
		update.put("issues", List.of());
		return update;
	}

	private static Map<String, Object> clarify(NodeContext ctx, CaseState state) {
		List<Object> issues = state.list("issues");
		Map<String, Object> update = audit(ctx, "Stub: would ask the submitter about " + issues.size() + " issue(s)");
		update.put(CaseState.CLARIFY_ROUNDS, state.clarifyRounds() + 1);
		return update;
	}

	/** The kind of action comes from the node's config ({@code actionType}), e.g. CHASE_MESSAGE. */
	private static Map<String, Object> drafter(NodeContext ctx, CaseState state) {
		String type = String.valueOf(ctx.node().config().getOrDefault("actionType", "PROPOSAL"));
		Map<String, Object> update = audit(ctx, "Stub: drafted one " + type);
		update.put(ctx.agent().spec().output().writeTo(),
				List.of(new ProposedAction(type, "Stub " + type.toLowerCase().replace('_', ' ') + " for review")));
		return update;
	}

	private static Map<String, Object> audit(NodeContext ctx, String note) {
		Map<String, Object> update = new LinkedHashMap<>();
		update.put("audit", List.of(new AuditEntry(ctx.node().id(), note)));
		return update;
	}

	private static NodeFactory node(String type, Duration pause, BiFunction<NodeContext, CaseState, Map<String, Object>> work) {
		return factory(NodeFactory.Kind.NODE, type, pause, work);
	}

	private static NodeFactory agent(String type, Duration pause, BiFunction<NodeContext, CaseState, Map<String, Object>> work) {
		return factory(NodeFactory.Kind.AGENT, type, pause, work);
	}

	private static NodeFactory factory(NodeFactory.Kind kind, String type, Duration pause,
			BiFunction<NodeContext, CaseState, Map<String, Object>> work) {
		return new NodeFactory() {

			@Override
			public Kind kind() {
				return kind;
			}

			@Override
			public String type() {
				return type;
			}

			@Override
			public NodeAction<CaseState> create(NodeContext context) {
				return state -> {
					if (!pause.isZero()) {
						Thread.sleep(pause);
					}
					return work.apply(context, state);
				};
			}
		};
	}
}

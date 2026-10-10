package com.rootstock.core.graph.nodes;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.graph.PackGraphLoader;
import com.rootstock.core.graph.stub.StubNodes;
import com.rootstock.core.ontology.ConstraintChecker;
import com.rootstock.core.ontology.JsonSchemaGenerator;
import com.rootstock.core.ontology.RequiredFields;
import com.rootstock.core.rules.RuleContext;
import com.rootstock.core.rules.RuleEngine;
import com.rootstock.core.rules.RuleKind;
import com.rootstock.core.rules.RuleKinds;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.bsc.langgraph4j.action.NodeAction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code rules} node (validate): checks a case in four layers, cheapest and
 * most certain first, and replaces the issues channel with everything found.
 *
 * <ol>
 * <li><b>Structural</b>: each record an extraction agent produced still matches
 * its schema (types, allowed codes), which matters once a reviewer has edited
 * it, and every field the ontology requires is there. A missing field is
 * BLOCKING and the submitter can answer it; a malformed one is for the reviewer.</li>
 * <li><b>Semantic</b>: the ontology's constraints (e.g. risk flags require HIGH
 * urgency). BLOCKING; the submitter can answer.</li>
 * <li><b>Business</b>: the pack's rules.yaml, built from Rootstock's rule kinds.</li>
 * <li><b>Judgment</b>: the judge agent named in the node's config
 * ({@code judge: consistency-judge}), comparing the record with the input.
 * Its findings are warnings; if it cannot run, that is a warning too.</li>
 * </ol>
 *
 * Which records to check needs no configuration: every agent in the graph
 * whose output has a projection writes a record of that shape. Until a pack's
 * rules drive every route, the {@code simulate} run option still adds the
 * stubs' issues.
 */
public final class RulesNode implements NodeFactory {

	public static final String TYPE = "rules";
	public static final String REQUIRED_FIELD = "required-field";
	public static final String SCHEMA = "schema";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Map<String, RuleKind> kinds;
	private final Function<String, Optional<NodeFactory>> agentTypes;
	private final Clock clock;
	private final RequiredFields requiredFields = new RequiredFields();
	private final ConstraintChecker constraints = new ConstraintChecker();

	/**
	 * @param kinds      the rule kinds the pack's rules may use
	 * @param agentTypes finds the factory of an agent type (for the judge); looked up when the graph is built
	 */
	public RulesNode(Map<String, RuleKind> kinds, Function<String, Optional<NodeFactory>> agentTypes, Clock clock) {
		this.kinds = kinds;
		this.agentTypes = agentTypes;
		this.clock = clock;
	}

	/** Built-in rule kinds, no judge. */
	public RulesNode() {
		this(RuleKinds.of(List.of()), type -> Optional.empty(), Clock.systemDefaultZone());
	}

	@Override
	public Kind kind() {
		return Kind.NODE;
	}

	@Override
	public String type() {
		return TYPE;
	}

	private record Checked(AgentDefinition.Output output, Schema schema) {
	}

	@Override
	public NodeAction<CaseState> create(NodeContext context) {
		List<Checked> records = context.ontology() == null ? List.of()
				: context.graph().agents().values().stream().map(a -> a.spec().output())
						.filter(o -> o.projection() != null)
						.map(o -> new Checked(o, SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
								.getSchema(new JsonSchemaGenerator().generateJson(context.ontology(), o.projection(),
										JsonSchemaGenerator.Mode.EXTRACTION))))
						.toList();
		RuleEngine engine = RuleEngine.build(context.graph().rules(), kinds);
		Judge judge = judge(context);

		return state -> {
			Map<String, List<CaseIssue>> found = new LinkedHashMap<>();
			CaseIssue.LAYERS.forEach(l -> found.put(l, new ArrayList<>()));
			List<String> notes = new ArrayList<>();
			List<Object> judgeAudit = new ArrayList<>();
			boolean anyRecord = false;

			for (Checked c : records) {
				Object record = state.value(c.output().writeTo()).orElse(null);
				if (!(record instanceof Map<?, ?>)) {
					continue;
				}
				anyRecord = true;
				found.get(CaseIssue.STRUCTURAL).addAll(structural(context, c, record));
				for (ConstraintChecker.Violation v : constraints.check(context.ontology(), c.output().projection(),
						record)) {
					String field = v.failed().getFirst();
					String at = (v.path().isEmpty() ? "" : v.path() + ".") + field.substring(field.indexOf('.') + 1);
					found.get(CaseIssue.SEMANTIC).add(new CaseIssue(v.constraint().id(), CaseIssue.BLOCKING,
							CaseIssue.SUBMITTER, v.constraint().description() != null ? v.constraint().description()
									: "Breaks " + v.constraint().id() + ": " + v.failed(),
							at, CaseIssue.SEMANTIC));
				}
			}
			if (anyRecord) {
				found.get(CaseIssue.BUSINESS).addAll(engine.evaluate(new RuleContext(state, LocalDate.now(clock))));
				if (judge != null) {
					found.get(CaseIssue.JUDGMENT).addAll(judge.run(state, judgeAudit));
				}
			}

			List<CaseIssue> issues = new ArrayList<>();
			for (Map.Entry<String, List<CaseIssue>> layer : found.entrySet()) {
				List<CaseIssue> list = layer.getValue();
				issues.addAll(list);
				if (anyRecord) {
					notes.add(layer.getKey().toLowerCase() + " " + (list.isEmpty() ? "ok" : list.size() + " "
							+ list.stream().map(CaseIssue::ruleId).distinct().toList()));
				}
			}
			StubNodes.Simulated simulated = StubNodes.simulated(state);
			if (simulated != null) {
				issues.addAll(simulated.issues());
				notes.add(simulated.note());
			}
			Map<String, Object> update = AuditEntry.update(context.node().id(),
					notes.isEmpty() ? "No records to check yet; no issues" : String.join("; ", notes));
			if (!judgeAudit.isEmpty()) {
				List<Object> audit = new ArrayList<>(judgeAudit);
				audit.addAll((List<?>) update.get(AuditEntry.CHANNEL));
				update.put(AuditEntry.CHANNEL, List.copyOf(audit));
			}
			// Always the whole list: each validation pass replaces the last one's issues.
			update.put("issues", List.copyOf(issues));
			return update;
		};
	}

	private List<CaseIssue> structural(NodeContext context, Checked c, Object record) {
		List<CaseIssue> out = new ArrayList<>();
		JsonNode node = JSON.valueToTree(record);
		List<Error> errors = c.schema().validate(node, ctx -> ctx.executionConfig(e -> e.formatAssertionsEnabled(true)));
		for (Error e : errors) {
			String at = PackGraphLoader.location(e);
			out.add(new CaseIssue(SCHEMA, CaseIssue.BLOCKING, CaseIssue.REVIEWER, "Not a valid value: " + e.getMessage(),
					at.isEmpty() ? null : at, CaseIssue.STRUCTURAL));
		}
		for (RequiredFields.Missing m : requiredFields.missing(context.ontology(), c.output().projection(), record)) {
			out.add(new CaseIssue(REQUIRED_FIELD, CaseIssue.BLOCKING, CaseIssue.SUBMITTER,
					"Missing " + m.path() + (m.description() == null ? "" : " (" + m.description() + ")"), m.path(),
					CaseIssue.STRUCTURAL));
		}
		return out;
	}

	/** The judge named in the node's config, ready to run; null when there is none. */
	private Judge judge(NodeContext context) {
		Object name = context.node().config().get("judge");
		if (name == null) {
			return null;
		}
		AgentDefinition agent = context.graph().agents().get(String.valueOf(name));
		Optional<NodeFactory> factory = agent == null ? Optional.empty() : agentTypes.apply(agent.spec().type());
		if (factory.isEmpty()) {
			return null;
		}
		NodeAction<CaseState> action = factory.get().create(new NodeContext(context.pack(), context.node(), agent,
				context.ontology(), context.graph()));
		return new Judge(agent, action);
	}

	private record Judge(AgentDefinition agent, NodeAction<CaseState> action) {

		/** The judge's issues; a judge that fails is a warning, never a failed case. */
		List<CaseIssue> run(CaseState state, List<Object> audit) {
			try {
				Map<String, Object> update = action.apply(state);
				if (update.get(AuditEntry.CHANNEL) instanceof List<?> entries) {
					audit.addAll(entries);
				}
				Object issues = update.get(agent.spec().output().writeTo());
				return issues instanceof List<?> l ? l.stream().filter(CaseIssue.class::isInstance)
						.map(CaseIssue.class::cast).toList() : List.of();
			}
			catch (Exception e) {
				return List.of(new CaseIssue(agent.name(), CaseIssue.WARNING, CaseIssue.REVIEWER,
						"The consistency check could not run (" + e.getMessage() + "); check the record against the "
								+ "input by hand", null, CaseIssue.JUDGMENT));
			}
		}
	}
}

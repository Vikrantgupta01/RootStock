package com.rootstock.core.graph.nodes;

import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.graph.stub.StubNodes;
import com.rootstock.core.ontology.RequiredFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.action.NodeAction;

/**
 * The {@code rules} node (validate). So far it runs one layer: every field the
 * ontology requires must be in the record an extraction agent produced. Each
 * one missing is a BLOCKING issue the submitter can answer, so the graph asks
 * them (clarify) rather than anyone inventing a value. The pack's own rules and
 * the judge come later (Iteration 8); until then the {@code simulate} run
 * option still raises the stubs' issues.
 *
 * <p>Which records to check needs no configuration: every agent in the graph
 * whose output has a projection writes a record of that shape.
 */
public final class RulesNode implements NodeFactory {

	public static final String TYPE = "rules";
	public static final String REQUIRED_FIELD = "required-field";

	private final RequiredFields requiredFields = new RequiredFields();

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
		List<AgentDefinition.Output> records = context.ontology() == null ? List.of()
				: context.graph().agents().values().stream().map(a -> a.spec().output())
						.filter(o -> o.projection() != null).toList();
		return state -> {
			List<CaseIssue> issues = new ArrayList<>();
			List<String> notes = new ArrayList<>();
			for (AgentDefinition.Output output : records) {
				Object record = state.value(output.writeTo()).orElse(null);
				if (!(record instanceof Map<?, ?>)) {
					continue;
				}
				List<RequiredFields.Missing> missing = requiredFields.missing(context.ontology(), output.projection(),
						record);
				missing.forEach(m -> issues.add(new CaseIssue(REQUIRED_FIELD, CaseIssue.BLOCKING, CaseIssue.SUBMITTER,
						"Missing " + m.path() + (m.description() == null ? "" : " (" + m.description() + ")"), m.path())));
				notes.add(output.writeTo() + ": " + (missing.isEmpty() ? "nothing required is missing"
						: missing.size() + " required field(s) missing " + missing.stream()
								.map(RequiredFields.Missing::path).toList()));
			}
			StubNodes.Simulated simulated = StubNodes.simulated(state);
			if (simulated != null) {
				issues.addAll(simulated.issues());
				notes.add(simulated.note());
			}
			Map<String, Object> update = AuditEntry.update(context.node().id(),
					notes.isEmpty() ? "No records to check yet; no issues" : String.join("; ", notes));
			// Always the whole list: each validation pass replaces the last one's issues.
			update.put("issues", List.copyOf(issues));
			return update;
		};
	}
}

package com.rootstock.core.graph.nodes;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseParkedException;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.graph.ProposedAction;
import com.rootstock.core.llm.LlmService;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.PromptTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.action.NodeAction;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code drafter} agent type: proposes what should happen next, for a
 * reviewer to approve: referrals, a follow-up, a message. One model call
 * over the agent's inputs (e.g. the record, what enrich looked up, the issues).
 *
 * <p>Which kinds of action it may propose is the node's config:
 * {@code actionTypes: [REFERRAL, FOLLOW_UP]} or {@code actionType: CHASE_MESSAGE}.
 * The reply must match {@code {"actions": [{type, summary, details}]}} with only
 * those types (given to the prompt as {@code {{schema}}} and
 * {@code {{actionTypes}}}); one that doesn't is sent back with what was wrong,
 * up to {@code limits.retries} times (default 1), then the case is parked.
 *
 * <p>Writes the list of {@link ProposedAction}s to its output channel, replacing
 * any earlier draft when the channel's reducer is {@code replace}.
 */
public final class DrafterAgent implements NodeFactory {

	public static final String TYPE = "drafter";

	static final int DEFAULT_RETRIES = 1;
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final LlmService llm;
	private final PromptRegistry prompts;
	private final Clock clock;

	public DrafterAgent(LlmService llm, PromptRegistry prompts, Clock clock) {
		this.llm = llm;
		this.prompts = prompts;
		this.clock = clock;
	}

	@Override
	public Kind kind() {
		return Kind.AGENT;
	}

	@Override
	public String type() {
		return TYPE;
	}

	/** The action types the node allows: {@code actionTypes} (a list) or {@code actionType} (one). */
	static List<String> actionTypes(Map<String, Object> config) {
		Object many = config.get("actionTypes");
		if (many instanceof Collection<?> c && !c.isEmpty()) {
			return c.stream().map(String::valueOf).toList();
		}
		Object one = config.get("actionType");
		return one == null ? List.of() : List.of(String.valueOf(one));
	}

	static String schema(List<String> types) {
		return """
				{
				  "type": "object",
				  "additionalProperties": false,
				  "required": ["actions"],
				  "properties": {
				    "actions": {
				      "type": "array",
				      "items": {
				        "type": "object",
				        "additionalProperties": false,
				        "required": ["type", "summary", "details"],
				        "properties": {
				          "type": { "enum": %s },
				          "summary": { "type": "string", "description": "One line a coordinator reads." },
				          "details": { "type": "object", "description": "What carrying it out needs, as the instructions say." }
				        }
				      }
				    }
				  }
				}""".formatted(JSON.writeValueAsString(types));
	}

	@Override
	public NodeAction<CaseState> create(NodeContext context) {
		AgentDefinition agent = context.agent();
		AgentDefinition.Spec spec = agent.spec();
		List<String> types = actionTypes(context.node().config());
		if (spec.prompt() == null || types.isEmpty()) {
			throw new IllegalStateException("Agent '" + agent.name() + "' (" + TYPE + ") needs a prompt, and node '"
					+ context.node().id() + "' needs actionTypes (or actionType) in its config");
		}
		String schemaJson = schema(types);
		Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaJson);
		AgentDefinition.Limits limits = spec.limits();
		int retries = limits == null || limits.retries() == null ? DEFAULT_RETRIES : limits.retries();
		Duration timeout = limits == null || limits.timeoutSeconds() == null ? DEFAULT_TIMEOUT
				: Duration.ofSeconds(limits.timeoutSeconds());

		return state -> {
			Map<String, String> values = new LinkedHashMap<>();
			spec.input().forEach((name, path) -> {
				Object v = StructuredExtraction.read(state, path);
				values.put(name, v == null ? "" : v instanceof String s ? s : JSON.writeValueAsString(v));
			});
			values.put("schema", schemaJson);
			values.put("actionTypes", String.join(", ", types));
			values.put("today", LocalDate.now(clock).toString());
			PromptTemplate prompt = prompts.get(spec.prompt().name(), spec.prompt().label());
			List<PromptTemplate.Part> conversation = new ArrayList<>(prompt.render(values));

			List<String> problems = List.of();
			for (int attempt = 1; attempt <= retries + 1; attempt++) {
				String reply = llm.call(spec.model(), conversation, prompt, timeout);
				StructuredExtraction.Attempt result = StructuredExtraction.check(reply, schema);
				if (result.problems().isEmpty()) {
					List<ProposedAction> actions = actions(result.record());
					Map<String, Object> update = AuditEntry.update(context.node().id(), "Drafted " + actions.size()
							+ " action(s) " + actions.stream().map(ProposedAction::type).toList() + " with prompt "
							+ prompt.describe() + (attempt == 1 ? "" : ", on attempt " + attempt));
					update.put(spec.output().writeTo(), List.copyOf(actions));
					return update;
				}
				problems = result.problems();
				conversation.add(new PromptTemplate.Part("assistant", reply));
				conversation.add(new PromptTemplate.Part("user", StructuredExtraction.retryRequest(problems)));
			}
			throw new CaseParkedException("Agent '" + agent.name() + "': the draft did not match after "
					+ (retries + 1) + " attempt(s): " + String.join("; ", problems));
		};
	}

	@SuppressWarnings("unchecked")
	private static List<ProposedAction> actions(Map<String, Object> reply) {
		List<ProposedAction> out = new ArrayList<>();
		if (reply.get("actions") instanceof List<?> items) {
			for (Object item : items) {
				if (item instanceof Map<?, ?> m) {
					out.add(new ProposedAction(String.valueOf(m.get("type")), String.valueOf(m.get("summary")),
							m.get("details") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of()));
				}
			}
		}
		return out;
	}
}

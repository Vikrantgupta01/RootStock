package com.rootstock.core.graph.nodes;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.llm.LlmService;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.PromptTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.action.NodeAction;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code judge} agent type: one model call that compares a record with the
 * input it came from and reports anything invented, contradicted or missed.
 *
 * <p>The reply must match a fixed shape, given to the prompt as
 * {@code {{schema}}}: {@code {"issues": [{"path", "problem", "evidence"}]}}. A
 * reply that doesn't is sent back with what was wrong, up to
 * {@code limits.retries} times (default 1). Every finding is a WARNING for the
 * reviewer (layer JUDGMENT): a model's opinion never blocks a case by itself.
 * If the judge cannot give a usable answer, that is a warning too, so the
 * check is never silently missing.
 *
 * <p>Used through the validate node's {@code judge:} setting, or as a node of
 * its own; either way it writes its issues (a list) to its output channel.
 */
public final class JudgeAgent implements NodeFactory {

	public static final String TYPE = "judge";

	static final int DEFAULT_RETRIES = 1;
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

	/** What the model must reply with. */
	public static final String OUTPUT_SCHEMA = """
			{
			  "type": "object",
			  "additionalProperties": false,
			  "required": ["issues"],
			  "properties": {
			    "issues": {
			      "type": "array",
			      "description": "Each thing in the record that the input does not support, contradicts, or leaves out. Empty when the record is faithful.",
			      "items": {
			        "type": "object",
			        "additionalProperties": false,
			        "required": ["path", "problem"],
			        "properties": {
			          "path": { "type": ["string", "null"], "description": "The record field, e.g. assistance[0].amountAud; null for the record as a whole." },
			          "problem": { "type": "string", "description": "What is wrong, in one sentence a coordinator can act on." },
			          "evidence": { "type": ["string", "null"], "description": "The words in the input that show it, quoted; null if the input says nothing." }
			        }
			      }
			    }
			  }
			}""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final LlmService llm;
	private final PromptRegistry prompts;
	private final Clock clock;
	private final Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
			.getSchema(OUTPUT_SCHEMA);

	public JudgeAgent(LlmService llm, PromptRegistry prompts, Clock clock) {
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

	@Override
	public NodeAction<CaseState> create(NodeContext context) {
		AgentDefinition agent = context.agent();
		AgentDefinition.Spec spec = agent.spec();
		if (spec.prompt() == null) {
			throw new IllegalStateException("Agent '" + agent.name() + "' (" + TYPE + ") needs a prompt");
		}
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
			values.put("schema", OUTPUT_SCHEMA);
			values.put("today", LocalDate.now(clock).toString());
			PromptTemplate prompt = prompts.get(spec.prompt().name(), spec.prompt().label());
			List<PromptTemplate.Part> conversation = new ArrayList<>(prompt.render(values));

			List<CaseIssue> issues = null;
			List<String> problems = List.of();
			int attempts = 0;
			for (int attempt = 1; attempt <= retries + 1 && issues == null; attempt++) {
				attempts = attempt;
				String reply = llm.call(spec.model(), conversation, prompt, timeout);
				StructuredExtraction.Attempt result = StructuredExtraction.check(reply, schema);
				if (result.problems().isEmpty()) {
					issues = issues(agent.name(), result.record());
				}
				else {
					problems = result.problems();
					conversation.add(new PromptTemplate.Part("assistant", reply));
					conversation.add(new PromptTemplate.Part("user", StructuredExtraction.retryRequest(problems)));
				}
			}
			String note;
			if (issues == null) {
				issues = List.of(new CaseIssue(agent.name(), CaseIssue.WARNING, CaseIssue.REVIEWER,
						"The consistency check gave no usable answer after " + attempts + " attempt(s); check the "
								+ "record against the input by hand", null, CaseIssue.JUDGMENT));
				note = "No usable answer after " + attempts + " attempt(s): " + String.join("; ", problems);
			}
			else {
				note = (issues.isEmpty() ? "Found nothing inconsistent" : "Flagged " + issues.size() + " thing(s)")
						+ " with prompt " + prompt.describe() + (attempts == 1 ? "" : ", on attempt " + attempts);
			}
			Map<String, Object> update = AuditEntry.update(context.node().id(), "Judge " + agent.name() + ": " + note);
			update.put(spec.output().writeTo(), List.copyOf(issues));
			return update;
		};
	}

	private static List<CaseIssue> issues(String judge, Map<String, Object> reply) {
		List<CaseIssue> out = new ArrayList<>();
		if (reply.get("issues") instanceof List<?> items) {
			for (Object item : items) {
				if (!(item instanceof Map<?, ?> m) || !(m.get("problem") instanceof String problem) || problem.isBlank()) {
					continue;
				}
				String evidence = m.get("evidence") instanceof String e && !e.isBlank() ? e.strip() : null;
				String path = m.get("path") instanceof String p && !p.isBlank() ? p.strip() : null;
				out.add(new CaseIssue(judge, CaseIssue.WARNING, CaseIssue.REVIEWER,
						problem.strip() + (evidence == null ? "" : " (input: \"" + evidence + "\")"), path,
						CaseIssue.JUDGMENT));
			}
		}
		return out;
	}
}

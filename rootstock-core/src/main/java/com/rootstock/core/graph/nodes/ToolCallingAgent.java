package com.rootstock.core.graph.nodes;

import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.Lookup;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.llm.LlmService;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.PromptTemplate;
import com.rootstock.core.tools.ToolCallContext;
import com.rootstock.core.tools.ToolCallResult;
import com.rootstock.core.tools.ToolGateway;
import com.rootstock.core.tools.ToolSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code tool-calling} agent type: looks things up in a client system.
 *
 * <p>First the agent's {@code plan} runs: fixed lookups whose arguments come
 * from the case state (e.g. the guideline for each need), no model involved.
 * Then a bounded loop: the model sees the plan's results and may ask for more
 * lookups (e.g. find the household, then its history), at most
 * {@code limits.maxIterations} model calls (default 4); on the last one it is
 * told to answer with what it has.
 *
 * <p>Every call goes through the {@link ToolGateway} as the graph node, so the
 * node's allowlist in tools.yaml is enforced, and the model is offered only the
 * agent's own {@code tools.allow}; a request for anything else is refused
 * before it is sent. Tool failures are recorded and shown to the model, never
 * fatal: a missing lookup is for validate to judge.
 *
 * <p>Writes {@code {lookups, summary, modelCalls}} into its output channel:
 * every call with its arguments, status and result, and the model's summary.
 */
public final class ToolCallingAgent implements NodeFactory {

	public static final String TYPE = "tool-calling";

	static final int DEFAULT_MAX_ITERATIONS = 4;
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
	/** Per model turn, so one reply cannot fan out without limit. */
	static final int MAX_CALLS_PER_TURN = 5;
	/** How much of a tool's output the model sees. */
	static final int MAX_OUTPUT_FOR_MODEL = 8_000;

	static final String LAST_TURN = "That was your last lookup. Answer now with what you have; do not ask for more tools.";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final LlmService llm;
	private final PromptRegistry prompts;
	private final ToolGateway gateway;
	private final Clock clock;

	public ToolCallingAgent(LlmService llm, PromptRegistry prompts, ToolGateway gateway, Clock clock) {
		this.llm = llm;
		this.prompts = prompts;
		this.gateway = gateway;
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
		AgentDefinition.Spec spec = context.agent().spec();
		if (spec.tools() == null || spec.prompt() == null) {
			throw new IllegalStateException("Agent '" + context.agent().name() + "' (" + TYPE + ") needs tools and a prompt");
		}
		String node = context.node().id();
		List<String> allowed = spec.tools().allow();
		AgentDefinition.Limits limits = spec.limits();
		int maxIterations = limits == null || limits.maxIterations() == null ? DEFAULT_MAX_ITERATIONS
				: limits.maxIterations();
		Duration timeout = limits == null || limits.timeoutSeconds() == null ? DEFAULT_TIMEOUT
				: Duration.ofSeconds(limits.timeoutSeconds());

		return state -> {
			ToolCallContext call = new ToolCallContext(state.caseId(), null);
			List<Lookup> lookups = new ArrayList<>(plan(spec, node, state, call));
			int planned = lookups.size();

			List<ToolSpec> tools = allowed.stream().map(t -> gateway.describe(node, t)).flatMap(Optional::stream)
					.toList();
			Map<String, String> values = new LinkedHashMap<>();
			spec.input().forEach((name, path) -> values.put(name, text(StructuredExtraction.read(state, path))));
			values.put("lookups", JSON.writeValueAsString(lookups));
			values.put("today", LocalDate.now(clock).toString());
			PromptTemplate prompt = prompts.get(spec.prompt().name(), spec.prompt().label());
			List<Message> conversation = new ArrayList<>(LlmService.messages(prompt.render(values)));

			String summary = "";
			int modelCalls = 0;
			boolean stoppedAtLimit = false;
			for (int turn = 1; turn <= maxIterations; turn++) {
				if (turn == maxIterations && turn > 1) {
					conversation.add(new UserMessage(LAST_TURN));
				}
				AssistantMessage reply = llm.respond(spec.model(), conversation, tools, prompt, timeout);
				modelCalls++;
				conversation.add(reply);
				summary = reply.getText() == null ? "" : reply.getText().strip();
				if (!reply.hasToolCalls()) {
					stoppedAtLimit = false;
					break;
				}
				stoppedAtLimit = true;
				List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
				List<AssistantMessage.ToolCall> requested = reply.getToolCalls();
				for (int i = 0; i < requested.size(); i++) {
					AssistantMessage.ToolCall c = requested.get(i);
					Lookup lookup = i < MAX_CALLS_PER_TURN ? asked(c, allowed, node, call)
							: new Lookup("model", c.name(), Map.of(), "REFUSED", null,
									"More than " + MAX_CALLS_PER_TURN + " lookups in one turn");
					lookups.add(lookup);
					responses.add(new ToolResponseMessage.ToolResponse(c.id(), c.name(), forModel(lookup)));
				}
				conversation.add(ToolResponseMessage.builder().responses(responses).build());
			}
			if (stoppedAtLimit) {
				summary = (summary.isEmpty() ? "" : summary + "\n") + "(Stopped after " + modelCalls
						+ " model calls, the agent's limit.)";
			}

			Map<String, Object> output = new LinkedHashMap<>();
			output.put("lookups", List.copyOf(lookups));
			output.put("summary", summary);
			output.put("modelCalls", modelCalls);
			long notOk = lookups.stream().filter(l -> !l.status().equals(ToolCallResult.Status.OK.name())).count();
			Map<String, Object> update = AuditEntry.update(node, "Looked things up " + lookups.size() + " time(s): "
					+ planned + " planned, " + (lookups.size() - planned) + " asked for by the model"
					+ (notOk == 0 ? "" : ", " + notOk + " not OK") + "; " + modelCalls + " model call(s) with prompt "
					+ prompt.describe() + (stoppedAtLimit ? "; stopped at the limit" : ""));
			update.put(spec.output().writeTo(), output);
			return update;
		};
	}

	/**
	 * The plan's lookups, in order. A step is skipped (not sent) when an argument
	 * has no value, and a call identical to one already made (two needs of the
	 * same category) is made once.
	 */
	private List<Lookup> plan(AgentDefinition.Spec spec, String node, CaseState state, ToolCallContext call) {
		List<Lookup> out = new ArrayList<>();
		Set<String> made = new HashSet<>();
		for (AgentDefinition.PlanStep step : spec.plan()) {
			List<Object> items = step.forEach() == null ? Collections.singletonList(null)
					: StructuredExtraction.read(state, step.forEach()) instanceof List<?> l ? new ArrayList<>(l) : List.of();
			for (Object item : items) {
				Map<String, Object> arguments = new LinkedHashMap<>();
				List<String> missing = new ArrayList<>();
				step.with().forEach((name, path) -> {
					Object value = path.startsWith("$item.") ? field(item, path.substring("$item.".length()))
							: StructuredExtraction.read(state, path);
					if (value == null) {
						missing.add(name);
					}
					else {
						arguments.put(name, value);
					}
				});
				if (!missing.isEmpty()) {
					out.add(new Lookup("plan", step.tool(), arguments, "SKIPPED", null, "No value for " + missing));
					continue;
				}
				if (!made.add(step.tool() + " " + new TreeMap<>(arguments))) {
					continue;
				}
				out.add(lookup("plan", gateway.call(node, step.tool(), arguments, call), arguments));
			}
		}
		return out;
	}

	private Lookup asked(AssistantMessage.ToolCall c, List<String> allowed, String node, ToolCallContext call) {
		Map<String, Object> arguments;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = c.arguments() == null || c.arguments().isBlank() ? Map.of()
					: JSON.readValue(c.arguments(), LinkedHashMap.class);
			arguments = parsed;
		}
		catch (JacksonException e) {
			return new Lookup("model", c.name(), Map.of(), ToolCallResult.Status.TOOL_ERROR.name(), null,
					"The arguments are not valid JSON");
		}
		if (!allowed.contains(c.name())) {
			// Not even sent to the gateway: the agent's own list is narrower than the node's.
			return new Lookup("model", c.name(), arguments, "REFUSED", null,
					"'" + c.name() + "' is not one of this agent's tools " + allowed);
		}
		return lookup("model", gateway.call(node, c.name(), arguments, call), arguments);
	}

	private static Lookup lookup(String source, ToolCallResult r, Map<String, Object> arguments) {
		return new Lookup(source, r.tool(), new LinkedHashMap<>(arguments), r.status().name(), r.ok() ? parse(r.output()) : null,
				r.message());
	}

	/** JSON output as maps and lists (Serializable), anything else as text. */
	static Object parse(String output) {
		if (output == null) {
			return null;
		}
		String t = output.strip();
		if (t.startsWith("{") || t.startsWith("[")) {
			try {
				return JSON.readValue(t, Object.class);
			}
			catch (JacksonException e) {
				return output;
			}
		}
		return output;
	}

	private static String forModel(Lookup l) {
		if (!l.status().equals(ToolCallResult.Status.OK.name())) {
			return l.status() + ": " + l.message();
		}
		String text = l.result() instanceof String s ? s : JSON.writeValueAsString(l.result());
		return text.length() <= MAX_OUTPUT_FOR_MODEL ? text
				: text.substring(0, MAX_OUTPUT_FOR_MODEL) + "… (cut at " + MAX_OUTPUT_FOR_MODEL + " characters)";
	}

	private static Object field(Object item, String path) {
		Object current = item;
		for (String key : path.split("\\.")) {
			current = current instanceof Map<?, ?> m ? m.get(key) : null;
		}
		return current;
	}

	private static String text(Object value) {
		if (value == null) {
			return "";
		}
		return value instanceof String s ? s : JSON.writeValueAsString(value);
	}
}

package com.rootstock.core.graph.nodes;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.rootstock.core.graph.AgentDefinition;
import com.rootstock.core.graph.AuditEntry;
import com.rootstock.core.graph.CaseParkedException;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.NodeContext;
import com.rootstock.core.graph.NodeFactory;
import com.rootstock.core.llm.LlmService;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.PromptTemplate;
import com.rootstock.core.ontology.GlossaryRenderer;
import com.rootstock.core.ontology.JsonSchemaGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.action.NodeAction;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code structured-extraction} agent type: one model call that turns free
 * text into a record shaped by an ontology projection.
 *
 * <p>The prompt comes from Langfuse by name and label. Besides the agent's own
 * inputs ({@code input:} in its YAML, e.g. {@code notes: $.rawInput}) it can use
 * {@code {{schema}}} (the projection's schema in extraction mode, where a
 * required field may be null rather than invented), {@code {{glossary}}} (the
 * projection's vocabularies, definitions and synonyms) and {@code {{today}}}.
 *
 * <p>The reply must be JSON matching that schema. If it is not, the model is
 * shown what was wrong and asked again, up to {@code limits.retries} times
 * (default 2); after that the case is parked for a person. Fields still
 * missing are not this agent's concern: validate finds them and asks.
 */
public final class StructuredExtraction implements NodeFactory {

	public static final String TYPE = "structured-extraction";

	static final int DEFAULT_RETRIES = 2;
	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final LlmService llm;
	private final PromptRegistry prompts;
	private final Clock clock;

	public StructuredExtraction(LlmService llm, PromptRegistry prompts, Clock clock) {
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
		AgentDefinition.Spec spec = context.agent().spec();
		String agent = context.agent().name();
		String projection = spec.output().projection();
		if (projection == null || context.ontology() == null || spec.prompt() == null) {
			throw new IllegalStateException("Agent '" + agent + "' (" + TYPE + ") needs output.projection, a valid "
					+ "ontology and a prompt");
		}
		String schemaJson = new JsonSchemaGenerator().generateJson(context.ontology(), projection,
				JsonSchemaGenerator.Mode.EXTRACTION);
		Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaJson);
		String glossary = new GlossaryRenderer().render(context.ontology(), projection);
		AgentDefinition.Limits limits = spec.limits();
		int retries = limits == null || limits.retries() == null ? DEFAULT_RETRIES : limits.retries();
		Duration timeout = limits == null || limits.timeoutSeconds() == null ? DEFAULT_TIMEOUT
				: Duration.ofSeconds(limits.timeoutSeconds());

		return state -> {
			Map<String, String> values = new LinkedHashMap<>();
			spec.input().forEach((name, path) -> values.put(name, text(read(state, path))));
			values.put("schema", schemaJson);
			values.put("glossary", glossary);
			values.put("today", LocalDate.now(clock).toString());

			PromptTemplate prompt = prompts.get(spec.prompt().name(), spec.prompt().label());
			List<PromptTemplate.Part> conversation = new ArrayList<>(prompt.render(values));
			List<String> problems = List.of();
			for (int attempt = 1; attempt <= retries + 1; attempt++) {
				String reply = llm.call(spec.model(), conversation, prompt, timeout);
				Attempt result = check(reply, schema);
				if (result.problems().isEmpty()) {
					Map<String, Object> update = AuditEntry.update(context.node().id(), "Extracted "
							+ filled(result.record()) + " of " + result.record().size() + " top-level fields of '"
							+ projection + "' with prompt " + prompt.describe() + ", model profile '" + spec.model()
							+ "'" + (attempt == 1 ? "" : ", on attempt " + attempt));
					update.put(spec.output().writeTo(), result.record());
					return update;
				}
				problems = result.problems();
				conversation.add(new PromptTemplate.Part("assistant", reply));
				conversation.add(new PromptTemplate.Part("user", retryRequest(problems)));
			}
			throw new CaseParkedException("Agent '" + agent + "': the model's output did not match '" + projection
					+ "' after " + (retries + 1) + " attempt(s): " + String.join("; ", problems));
		};
	}

	/** @param problems empty when the reply is a record matching the schema */
	record Attempt(LinkedHashMap<String, Object> record, List<String> problems) {
	}

	static Attempt check(String reply, Schema schema) {
		String json = jsonObject(reply);
		if (json == null) {
			return new Attempt(null, List.of("the reply holds no JSON object"));
		}
		JsonNode node;
		try {
			node = JSON.readTree(json);
		}
		catch (JacksonException e) {
			return new Attempt(null, List.of("the reply is not valid JSON: " + e.getOriginalMessage()));
		}
		List<Error> errors = schema.validate(node, ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)));
		if (!errors.isEmpty()) {
			return new Attempt(null, errors.stream().map(Error::toString).distinct().limit(20).toList());
		}
		@SuppressWarnings("unchecked")
		LinkedHashMap<String, Object> record = JSON.treeToValue(node, LinkedHashMap.class);
		return new Attempt(record, List.of());
	}

	/** The JSON object in a reply, without any code fence or words around it. */
	static String jsonObject(String reply) {
		if (reply == null) {
			return null;
		}
		int start = reply.indexOf('{');
		int end = reply.lastIndexOf('}');
		return start < 0 || end < start ? null : reply.substring(start, end + 1);
	}

	static String retryRequest(List<String> problems) {
		return "That reply does not match the schema:\n- " + String.join("\n- ", problems)
				+ "\nReply again with only the corrected JSON object. Use null for anything the input does not say.";
	}

	private static long filled(Map<String, Object> record) {
		return record.values().stream()
				.filter(v -> v != null && !(v instanceof List<?> l && l.isEmpty()))
				.count();
	}

	/** A state path such as {@code $.rawInput} or {@code $.record.household}. */
	static Object read(CaseState state, String path) {
		String[] parts = path.substring(2).split("\\.");
		Object current = state.value(parts[0]).orElse(null);
		for (int i = 1; i < parts.length && current != null; i++) {
			current = current instanceof Map<?, ?> m ? m.get(parts[i]) : null;
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

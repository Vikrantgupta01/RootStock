package com.rootstock.runtime.observability;

/**
 * Span attribute keys Langfuse reads when ingesting OTLP.
 *
 * <p>Two families. The {@code gen_ai.*} keys are OpenTelemetry's GenAI semantic
 * conventions, which Spring AI already emits for model and token usage and which
 * Langfuse maps automatically. The {@code langfuse.*} keys are its own, and take
 * priority over the conventions where both exist -- they are how a span says
 * what it <em>is</em> rather than leaving Langfuse to infer it.
 *
 * <p>Trace-level attributes (name, session, user, tags, metadata) are read from
 * the <em>root</em> span of a trace, which is why {@link RequestTrace} creates
 * one explicitly rather than letting whichever span happens to be outermost
 * define the trace.
 */
public final class LangfuseAttributes {

	/** Prompt sent to the model. Langfuse renders it as messages when it is a JSON array of {role, content}. */
	public static final String PROMPT = "gen_ai.prompt";

	/** The model's reply. */
	public static final String COMPLETION = "gen_ai.completion";

	/** {@code generation}, {@code retriever}, {@code span}, ... An explicit valid type always wins over inference. */
	public static final String OBSERVATION_TYPE = "langfuse.observation.type";

	/** Input of a non-generation observation, as a JSON string. */
	public static final String OBSERVATION_INPUT = "langfuse.observation.input";

	/** Output of a non-generation observation, as a JSON string. */
	public static final String OBSERVATION_OUTPUT = "langfuse.observation.output";

	/** Prefix: {@code langfuse.observation.metadata.<key>} is filterable metadata on this span alone. */
	public static final String OBSERVATION_METADATA_PREFIX = "langfuse.observation.metadata.";

	/** DEBUG, DEFAULT, WARNING or ERROR; lets failed or refused steps stand out without opening them. */
	public static final String OBSERVATION_LEVEL = "langfuse.observation.level";

	/** Why an observation is at WARNING or ERROR, in words. */
	public static final String OBSERVATION_STATUS_MESSAGE = "langfuse.observation.status_message";

	public static final String LEVEL_WARNING = "WARNING";
	public static final String LEVEL_ERROR = "ERROR";

	/** Links a generation to the Langfuse prompt version it used. */
	public static final String PROMPT_NAME = "langfuse.observation.prompt.name";
	public static final String PROMPT_VERSION = "langfuse.observation.prompt.version";

	public static final String TRACE_NAME = "langfuse.trace.name";
	public static final String SESSION_ID = "langfuse.session.id";
	public static final String USER_ID = "langfuse.user.id";
	public static final String TRACE_TAGS = "langfuse.trace.tags";

	/** Prefix: {@code langfuse.trace.metadata.<key>} becomes a filterable field. */
	public static final String TRACE_METADATA_PREFIX = "langfuse.trace.metadata.";

	/** Keeps development traffic out of production dashboards. */
	public static final String ENVIRONMENT = "langfuse.environment";

	public static final String TYPE_GENERATION = "generation";
	public static final String TYPE_RETRIEVER = "retriever";
	public static final String TYPE_AGENT = "agent";
	public static final String TYPE_TOOL = "tool";

	private LangfuseAttributes() {
	}
}

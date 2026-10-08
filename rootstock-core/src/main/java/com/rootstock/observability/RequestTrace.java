package com.rootstock.observability;

import com.rootstock.auth.AuthContext;
import com.rootstock.rag.tenant.TenantContext;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Opens the one observation that is the root of a request's trace.
 *
 * <p>Langfuse reads a trace's name, session, user and tags from the <em>root</em>
 * span, so something has to own that role deliberately. HTTP server observations
 * are switched off in {@code application.yml} (they are noise in an LLM trace),
 * which leaves the observation started here outermost.
 *
 * <p>Every identifier is read <em>eagerly</em>, while the caller is still on the
 * request thread. {@link TenantContext} and {@link AuthContext} are ThreadLocals
 * cleared by the servlet filter, so a streaming response that finishes on a
 * reactive thread would otherwise find them empty -- the same trap
 * {@code ChatController.chatStream} already documents for conversation history.
 *
 * <p>Not conditional on Langfuse being enabled: an observation with no tracing
 * handler registered costs almost nothing, and making it conditional would mean
 * every caller needed a null check for a bean that may not exist.
 */
@Component
public class RequestTrace {

	/** Tag values for the "which feature is this" dimension. */
	public static final String SURFACE_CHAT = "chat";
	public static final String SURFACE_RAG = "rag";
	public static final String SURFACE_AGENT = "agent";
	public static final String SURFACE_TOOLS = "tools";

	private final ObservationRegistry registry;
	private final String environment;
	private final String chatModel;

	public RequestTrace(ObservationRegistry registry, Environment springEnvironment) {
		this.registry = registry;
		// Keeps local and test traffic out of a production Langfuse dashboard.
		String[] profiles = springEnvironment.getActiveProfiles();
		this.environment = profiles.length == 0 ? "development" : profiles[0];
		// Langfuse records the model on each generation, which is where it belongs.
		// Repeating it as trace metadata makes "which model answered this" a filter
		// on the trace list rather than a drill-down into every child span. Read
		// from configuration because RagProfile.chatModelId is not wired to the
		// call yet -- the globally configured model is the one actually used.
		this.chatModel = springEnvironment.getProperty("spring.ai.bedrock.converse.chat.options.model");
	}

	/**
	 * Starts the root observation for a request.
	 *
	 * @param name    verb-first and free of ids, e.g. {@code answer-question} --
	 *                names are referenced by Langfuse filters and dashboards, so a
	 *                name containing a UUID makes every trace its own category
	 * @param surface {@link #SURFACE_CHAT}, {@link #SURFACE_RAG}, {@link #SURFACE_AGENT} or {@link #SURFACE_TOOLS}
	 */
	public Observation start(String name, String surface, UUID conversationId) {
		Observation observation = Observation.createNotStarted(name, registry);
		tag(observation, LangfuseAttributes.TRACE_NAME, name);
		tag(observation, LangfuseAttributes.ENVIRONMENT, environment);
		tag(observation, LangfuseAttributes.TRACE_TAGS, "[\"" + surface + "\"]");
		if (conversationId != null) {
			// A conversation is exactly a Langfuse session: many traces, one thread.
			tag(observation, LangfuseAttributes.SESSION_ID, conversationId.toString());
		}
		tag(observation, LangfuseAttributes.TRACE_METADATA_PREFIX + "tenant", TenantContext.getOrNull());
		tag(observation, LangfuseAttributes.TRACE_METADATA_PREFIX + "model", chatModel);

		AuthContext.Principal principal = AuthContext.getOrNull();
		if (principal != null) {
			// The Cognito `sub`, deliberately not the email: it identifies the user
			// for filtering and cost attribution without copying personal data into
			// a third-party system.
			tag(observation, LangfuseAttributes.USER_ID, principal.userId());
			tag(observation, LangfuseAttributes.TRACE_METADATA_PREFIX + "role",
					principal.role() == null ? null : principal.role().name());
		}
		return observation.start();
	}

	/**
	 * Binds this trace to a Langfuse session. Set after the fact, because the
	 * conversation is resolved inside the traced call rather than handed to it.
	 */
	public static void session(Observation observation, UUID conversationId) {
		if (conversationId != null) {
			tag(observation, LangfuseAttributes.SESSION_ID, conversationId.toString());
		}
	}

	/** Binds this trace to a Langfuse session by any id, e.g. a case id. */
	public static void session(Observation observation, String sessionId) {
		tag(observation, LangfuseAttributes.SESSION_ID, sessionId);
	}

	/** Adds a filterable metadata field to this observation alone, not the trace. */
	public static void spanMetadata(Observation observation, String key, String value) {
		tag(observation, LangfuseAttributes.OBSERVATION_METADATA_PREFIX + key, value);
	}

	/** Marks the observation WARNING or ERROR, with the reason as its status message. */
	public static void level(Observation observation, String level, String statusMessage) {
		tag(observation, LangfuseAttributes.OBSERVATION_LEVEL, level);
		tag(observation, LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, statusMessage);
	}

	/** Adds a filterable trace-level metadata field. */
	public static void metadata(Observation observation, String key, String value) {
		tag(observation, LangfuseAttributes.TRACE_METADATA_PREFIX + key, value);
	}

	/** Declares what kind of observation this is, e.g. {@code retriever}. Explicit beats inferred. */
	public static void type(Observation observation, String observationType) {
		tag(observation, LangfuseAttributes.OBSERVATION_TYPE, observationType);
	}

	/** What the caller asked for, shown as the trace's input in Langfuse. */
	public static void input(Observation observation, String value) {
		tag(observation, LangfuseAttributes.OBSERVATION_INPUT, value);
	}

	/** What the caller got back, shown as the trace's output. */
	public static void output(Observation observation, String value) {
		tag(observation, LangfuseAttributes.OBSERVATION_OUTPUT, value);
	}

	static void tag(Observation observation, String key, String value) {
		if (observation != null && StringUtils.hasText(value)) {
			observation.highCardinalityKeyValue(KeyValue.of(key, value));
		}
	}
}

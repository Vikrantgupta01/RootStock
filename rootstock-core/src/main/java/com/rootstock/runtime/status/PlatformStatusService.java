package com.rootstock.runtime.status;

import com.rootstock.autoconfig.mcp.McpConnections;
import com.rootstock.core.auth.AuthProperties;
import com.rootstock.runtime.observability.LangfuseLinks;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Checks each service Rootstock depends on, all at once, each with a time limit,
 * so one slow service turns red instead of hanging the page. Every check is a
 * real round trip, not a configuration lookup: green means it answered just now.
 */
@Service
public class PlatformStatusService implements DisposableBean {

	static final Duration TIMEOUT = Duration.ofSeconds(15);

	/**
	 * @param detail what was checked and found, in a sentence
	 * @param link   where to look further (the latest Langfuse trace); may be null
	 */
	public record Check(String name, boolean ok, String detail, long latencyMs, String link) {
	}

	public record Status(boolean allOk, List<Check> checks) {
	}

	private final ObjectProvider<ChatModel> chatModel;
	private final JdbcTemplate jdbc;
	private final AuthProperties auth;
	private final LangfuseLinks langfuse;
	private final McpConnections mcp;
	private final Function<String, Map<?, ?>> fetchJson;
	private final Duration timeout;
	private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

	@Autowired
	public PlatformStatusService(ObjectProvider<ChatModel> chatModel, JdbcTemplate jdbc, AuthProperties auth,
			LangfuseLinks langfuse, McpConnections mcp) {
		this(chatModel, jdbc, auth, langfuse, mcp, url -> RestClient.create().get().uri(url).retrieve().body(Map.class),
				TIMEOUT);
	}

	/** For tests: no real HTTP, and a short time limit. */
	PlatformStatusService(ObjectProvider<ChatModel> chatModel, JdbcTemplate jdbc, AuthProperties auth,
			LangfuseLinks langfuse, McpConnections mcp, Function<String, Map<?, ?>> fetchJson, Duration timeout) {
		this.chatModel = chatModel;
		this.jdbc = jdbc;
		this.auth = auth;
		this.langfuse = langfuse;
		this.mcp = mcp;
		this.fetchJson = fetchJson;
		this.timeout = timeout;
	}

	public Status check() {
		List<CompletableFuture<Check>> running = new ArrayList<>();
		running.add(run("Bedrock", this::bedrock));
		running.add(run("RDS", this::rds));
		running.add(run("Cognito", this::cognito));
		running.add(run("Langfuse", this::langfuse));
		for (String connection : mcp.names().stream().sorted().toList()) {
			running.add(run("MCP: " + connection, () -> mcpConnection(connection)));
		}
		List<Check> checks = running.stream().map(CompletableFuture::join).toList();
		return new Status(checks.stream().allMatch(Check::ok), checks);
	}

	private record Outcome(String detail, String link) {
	}

	private CompletableFuture<Check> run(String name, Supplier<Outcome> check) {
		long start = System.nanoTime();
		return CompletableFuture.supplyAsync(check, pool)
				.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
				.handle((outcome, failure) -> {
					long ms = (System.nanoTime() - start) / 1_000_000;
					return failure == null ? new Check(name, true, outcome.detail(), ms, outcome.link())
							: new Check(name, false, reason(failure), ms, null);
				});
	}

	private Outcome bedrock() {
		ChatModel model = chatModel.getIfAvailable();
		if (model == null) {
			throw new IllegalStateException("No chat model is configured");
		}
		String answer = model.call(new Prompt("Reply with the single word OK.")).getResult().getOutput().getText();
		return new Outcome("The chat model answered \"" + abbreviate(answer) + "\"", null);
	}

	private Outcome rds() {
		jdbc.queryForObject("SELECT 1", Integer.class);
		String version = jdbc.queryForObject(
				"SELECT max(version) FROM flyway_schema_history WHERE success", String.class);
		return new Outcome("Database reachable; schema at migration V" + version, null);
	}

	private Outcome cognito() {
		AuthProperties.Cognito c = auth.cognito();
		Map<?, ?> jwks = fetchJson.apply(c.issuer() + "/.well-known/jwks.json");
		Object keys = jwks == null ? null : jwks.get("keys");
		int count = keys instanceof List<?> list ? list.size() : 0;
		if (count == 0) {
			throw new IllegalStateException("User pool " + c.userPoolId() + " published no signing keys");
		}
		return new Outcome("User pool " + c.userPoolId() + " published " + count
				+ " signing keys; you are signed in with it", null);
	}

	private Outcome langfuse() {
		if (!langfuse.configured()) {
			throw new IllegalStateException("Langfuse is not configured (keys missing or disabled)");
		}
		return langfuse.latestTraceId()
				.map(id -> new Outcome("Keys accepted; latest trace in the last day", langfuse.traceUrl(id)))
				.orElseGet(() -> new Outcome("Keys accepted; no traces in the last day yet", null));
	}

	private Outcome mcpConnection(String connection) {
		List<String> tools = mcp.listTools(connection).stream().map(McpSchema.Tool::name).sorted().toList();
		return new Outcome(tools.size() + " tools at " + mcp.url(connection) + ": " + String.join(", ", tools), null);
	}

	private String reason(Throwable failure) {
		Throwable t = failure;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		if (t instanceof java.util.concurrent.TimeoutException) {
			return "No answer within " + timeout.toMillis() / 1000.0 + " s";
		}
		return t.getMessage() == null ? t.getClass().getSimpleName() : abbreviate(t.getMessage());
	}

	private static String abbreviate(String text) {
		String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
		return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
	}

	@Override
	public void destroy() {
		pool.shutdownNow();
	}
}

package com.rootstock.autoconfig.mcp;

import com.rootstock.core.tools.ToolCallContext;
import com.rootstock.core.tools.ToolDefinition;
import com.rootstock.core.tools.ToolInvoker;
import com.rootstock.core.tools.ToolSpec;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls a tool over MCP, on a session holding a token for the tool's access
 * level: a WRITE tool is the only thing that ever causes a write token to be
 * requested.
 *
 * <p>Maps MCP's two ways of saying no onto the gateway's: a tool result marked
 * {@code isError}, or a JSON-RPC error (unknown tool, refused by the server),
 * both become {@link ToolErrorException}. Anything else (unreachable, timed out,
 * no token) propagates as "unavailable".
 */
public class McpToolInvoker implements ToolInvoker {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final McpConnections connections;
	/** What each connection offers, asked once and again only when a tool is not found. */
	private final Map<String, Map<String, McpSchema.Tool>> offered = new ConcurrentHashMap<>();

	public McpToolInvoker(McpConnections connections) {
		this.connections = connections;
	}

	@Override
	public Optional<ToolSpec> describe(ToolDefinition tool) {
		McpSchema.Tool remote = offered(tool.connection(), false).get(tool.remoteName());
		if (remote == null) {
			remote = offered(tool.connection(), true).get(tool.remoteName());
		}
		if (remote == null) {
			return Optional.empty();
		}
		// Through a map, so fields the schema leaves unset are dropped rather than sent as null.
		Map<String, Object> schema = new LinkedHashMap<>(JSON.convertValue(remote.inputSchema(), Map.class));
		schema.values().removeIf(Objects::isNull);
		return Optional.of(new ToolSpec(tool.name(), remote.description(), JSON.writeValueAsString(schema)));
	}

	private Map<String, McpSchema.Tool> offered(String connection, boolean refresh) {
		if (refresh) {
			offered.remove(connection);
		}
		return offered.computeIfAbsent(connection, c -> connections.listTools(c).stream()
				.collect(Collectors.toMap(McpSchema.Tool::name, t -> t, (a, b) -> a)));
	}

	@Override
	public String invoke(ToolDefinition tool, Map<String, Object> arguments, ToolCallContext context) {
		McpSchema.CallToolResult result;
		try {
			result = connections.session(tool.connection(), tool.access())
					.callTool(new McpSchema.CallToolRequest(tool.remoteName(), arguments));
		}
		catch (McpError ex) {
			throw new ToolErrorException(ex.getJsonRpcError() != null ? ex.getJsonRpcError().message() : ex.getMessage());
		}
		String text = text(result);
		if (Boolean.TRUE.equals(result.isError())) {
			throw new ToolErrorException(firstLine(text));
		}
		return text;
	}

	private static String text(McpSchema.CallToolResult result) {
		return result.content() == null ? "" : result.content().stream()
				.filter(McpSchema.TextContent.class::isInstance)
				.map(c -> ((McpSchema.TextContent) c).text())
				.collect(Collectors.joining("\n"));
	}

	/**
	 * Spring AI MCP servers repeat an error's message on a second line (message,
	 * then its cause's); one copy is enough for a reviewer.
	 */
	private static String firstLine(String text) {
		int newline = text.indexOf('\n');
		return newline < 0 ? text : text.substring(0, newline);
	}
}

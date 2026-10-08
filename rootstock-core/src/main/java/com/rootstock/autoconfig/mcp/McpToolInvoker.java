package com.rootstock.autoconfig.mcp;

import com.rootstock.core.tools.ToolCallContext;
import com.rootstock.core.tools.ToolDefinition;
import com.rootstock.core.tools.ToolInvoker;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.stream.Collectors;

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

	private final McpConnections connections;

	public McpToolInvoker(McpConnections connections) {
		this.connections = connections;
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

package com.rootstock.autoconfig.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rootstock.core.tools.ToolAccess;
import com.rootstock.core.tools.ToolCallContext;
import com.rootstock.core.tools.ToolDefinition;
import com.rootstock.core.tools.ToolInvoker.ToolErrorException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How MCP's answers become the gateway's outcomes, and which token a call uses. */
class McpToolInvokerTest {

	private final McpConnections connections = mock(McpConnections.class);
	private final McpSyncClient readSession = mock(McpSyncClient.class);
	private final McpSyncClient writeSession = mock(McpSyncClient.class);
	private final McpToolInvoker invoker = new McpToolInvoker(connections);

	private final ToolDefinition read = new ToolDefinition("find_household", "client", "find_household", ToolAccess.READ);
	private final ToolDefinition write = new ToolDefinition("create_case", "client", "create_case", ToolAccess.WRITE);

	{
		given(connections.session("client", ToolAccess.READ)).willReturn(readSession);
		given(connections.session("client", ToolAccess.WRITE)).willReturn(writeSession);
	}

	@Test
	void returnsTheToolsText() {
		given(readSession.callTool(any())).willReturn(result(false, "{\"matches\":[]}"));

		assertThat(invoker.invoke(read, Map.of(), ToolCallContext.NONE)).isEqualTo("{\"matches\":[]}");
	}

	@Test
	void aReadToolNeverOpensAWriteSession() {
		given(readSession.callTool(any())).willReturn(result(false, "ok"));

		invoker.invoke(read, Map.of(), ToolCallContext.NONE);

		verify(connections, never()).session("client", ToolAccess.WRITE);
	}

	@Test
	void aWriteToolUsesTheWriteSession() {
		given(writeSession.callTool(any())).willReturn(result(false, "ok"));

		invoker.invoke(write, Map.of(), ToolCallContext.NONE);

		verify(writeSession).callTool(any());
		verify(readSession, never()).callTool(any());
	}

	@Test
	void anIsErrorResultIsAToolErrorShownOnce() {
		given(readSession.callTool(any())).willReturn(result(true, "Access Denied\nAccess Denied"));

		assertThatThrownBy(() -> invoker.invoke(read, Map.of(), ToolCallContext.NONE))
				.isInstanceOf(ToolErrorException.class)
				.hasMessage("Access Denied");
	}

	@Test
	void aJsonRpcErrorIsAToolError() {
		given(readSession.callTool(any())).willThrow(new McpError(
				new McpSchema.JSONRPCResponse.JSONRPCError(-32602, "Unknown tool: find_houshold", null)));

		assertThatThrownBy(() -> invoker.invoke(read, Map.of(), ToolCallContext.NONE))
				.isInstanceOf(ToolErrorException.class)
				.hasMessageContaining("Unknown tool");
	}

	private static McpSchema.CallToolResult result(boolean error, String text) {
		return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(text))).isError(error).build();
	}
}

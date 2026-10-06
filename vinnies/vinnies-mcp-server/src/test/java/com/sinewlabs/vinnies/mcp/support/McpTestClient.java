package com.sinewlabs.vinnies.mcp.support;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

/** A real MCP client over Streamable HTTP, as Inspector and Rootstock connect. */
public final class McpTestClient {

	private McpTestClient() {
	}

	public static McpSyncClient connect(int port) {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
				.endpoint("/mcp")
				.build();
		McpSyncClient client = McpClient.sync(transport).build();
		client.initialize();
		return client;
	}

	/** Calls a tool and returns its single text result, failing if the tool reported an error. */
	public static String callText(McpSyncClient client, String tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
		if (Boolean.TRUE.equals(result.isError())) {
			throw new AssertionError(tool + " returned an error: " + result.content());
		}
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}
}

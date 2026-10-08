package com.rootstock.autoconfig.mcp;

import com.rootstock.core.tools.ToolAccess;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.DisposableBean;

/**
 * One MCP client session per connection and access level, opened on first use
 * and kept. Every HTTP request carries a current bearer token for that access
 * level, so an expired token is replaced transparently, mid-session.
 *
 * <p>A session that fails to open is not kept, so the next call tries again: a
 * client system that was down at startup is picked up once it is back.
 */
public class McpConnections implements DisposableBean {

	private final Map<String, ToolsProperties.Connection> connections;
	private final ClientCredentialsTokens tokens;
	private final Map<String, McpSyncClient> sessions = new ConcurrentHashMap<>();

	public McpConnections(Map<String, ToolsProperties.Connection> connections, ClientCredentialsTokens tokens) {
		this.connections = Map.copyOf(connections);
		this.tokens = tokens;
	}

	public Set<String> names() {
		return connections.keySet();
	}

	public String url(String connection) {
		ToolsProperties.Connection c = require(connection);
		return c.url() + c.endpoint();
	}

	/** The tools the server offers, as it describes them; uses a read session. */
	public List<McpSchema.Tool> listTools(String connection) {
		return session(connection, ToolAccess.READ).listTools().tools();
	}

	/** An open session for this connection and access level, opening one if needed. */
	public McpSyncClient session(String connection, ToolAccess access) {
		return sessions.computeIfAbsent(key(connection, access), k -> open(connection, access));
	}

	private McpSyncClient open(String connection, ToolAccess access) {
		ToolsProperties.Connection c = require(connection);
		var transport = HttpClientStreamableHttpTransport.builder(c.url())
				.endpoint(c.endpoint())
				.httpRequestCustomizer((request, method, uri, body, context) ->
						request.header("Authorization", "Bearer " + tokens.token(connection, access)))
				.build();
		McpSyncClient client = McpClient.sync(transport)
				.clientInfo(new McpSchema.Implementation("rootstock", "0.0.1"))
				.requestTimeout(c.requestTimeout())
				.initializationTimeout(c.requestTimeout())
				.build();
		try {
			client.initialize();
			return client;
		}
		catch (RuntimeException ex) {
			client.close();
			throw ex;
		}
	}

	private ToolsProperties.Connection require(String connection) {
		ToolsProperties.Connection c = connections.get(connection);
		if (c == null) {
			throw new IllegalArgumentException("No MCP connection named '" + connection + "'; configured: "
					+ connections.keySet());
		}
		return c;
	}

	private static String key(String connection, ToolAccess access) {
		return connection + "/" + access;
	}

	@Override
	public void destroy() {
		sessions.values().forEach(McpSyncClient::closeGracefully);
		sessions.clear();
	}
}

package com.rootstock.autoconfig.mcp;

import com.rootstock.core.tools.ToolAccess;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code rootstock.tools.*}: the client systems Rootstock may call over
 * MCP. Rootstock ships none. A domain supplies them in a file named by
 * {@code ROOTSTOCK_TOOLS_FILE} (imported in application.yml), so this code never
 * names a client.
 *
 * @param connections by connection name, e.g. the name the domain's tools refer to
 * @param tools       every tool Rootstock may call. A list, not a map: Spring strips
 *                    characters such as {@code _} from map keys, which would
 *                    silently rename {@code find_household}
 * @param allowlists  node -> the logical tool names it may call
 * @param writeNodes  the only nodes where WRITE tools may be allowed
 */
@ConfigurationProperties(prefix = "rootstock.tools")
public record ToolsProperties(Map<String, Connection> connections, List<Tool> tools,
		Map<String, List<String>> allowlists, List<String> writeNodes) {

	public ToolsProperties {
		connections = connections == null ? Map.of() : Map.copyOf(connections);
		tools = tools == null ? List.of() : List.copyOf(tools);
		allowlists = allowlists == null ? Map.of() : Map.copyOf(allowlists);
		writeNodes = writeNodes == null ? List.of() : List.copyOf(writeNodes);
	}

	/**
	 * @param name       logical name used by nodes, e.g. {@code find_household}
	 * @param remoteName the name on the client system; defaults to {@code name}
	 */
	public record Tool(String name, String connection, String remoteName, ToolAccess access) {

		public String remoteNameOrName() {
			return remoteName == null || remoteName.isBlank() ? name : remoteName;
		}
	}

	/**
	 * One MCP server, reached over Streamable HTTP with OAuth2 client credentials.
	 *
	 * @param url        base URL, e.g. {@code http://localhost:8081}
	 * @param endpoint   MCP endpoint path on that server
	 * @param tokenUrl   the authorization server's token endpoint
	 * @param readScope  scope requested for read tools
	 * @param writeScope scope requested for write tools, only when a write is made
	 */
	public record Connection(
			String url,
			@DefaultValue("/mcp") String endpoint,
			String tokenUrl,
			String clientId,
			String clientSecret,
			String readScope,
			String writeScope,
			@DefaultValue("20s") Duration requestTimeout) {
	}
}

package com.rootstock.autoconfig.mcp;

import com.rootstock.core.tools.ToolCatalog;
import com.rootstock.core.tools.ToolDefinition;
import com.rootstock.core.tools.ToolGateway;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Wires Rootstock's MCP connections from {@code rootstock.tools.connections}.
 * With none configured this is inert: no tokens, no sessions, nothing logged.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ToolsProperties.class)
public class McpConfiguration {

	private static final Logger log = LoggerFactory.getLogger(McpConfiguration.class);

	@Bean
	ClientCredentialsTokens clientCredentialsTokens(ToolsProperties properties) {
		return new ClientCredentialsTokens(properties.connections(), null);
	}

	@Bean
	McpConnections mcpConnections(ToolsProperties properties, ClientCredentialsTokens tokens) {
		return new McpConnections(properties.connections(), tokens);
	}

	/** Checked at startup: unknown tools or a write tool outside a write node stop the app. */
	@Bean
	ToolCatalog toolCatalog(ToolsProperties properties) {
		Map<String, ToolDefinition> tools = new LinkedHashMap<>();
		for (ToolsProperties.Tool t : properties.tools()) {
			if (!properties.connections().containsKey(t.connection())) {
				throw new IllegalStateException("Tool '" + t.name() + "' uses unknown connection '" + t.connection()
						+ "'; configured: " + properties.connections().keySet());
			}
			if (t.access() == null) {
				throw new IllegalStateException("Tool '" + t.name() + "' has no access (READ or WRITE)");
			}
			if (tools.put(t.name(), new ToolDefinition(t.name(), t.connection(), t.remoteNameOrName(), t.access())) != null) {
				throw new IllegalStateException("Tool '" + t.name() + "' is defined twice");
			}
		}
		return new ToolCatalog(tools, properties.allowlists(), new HashSet<>(properties.writeNodes()));
	}

	@Bean
	ToolGateway toolGateway(ToolCatalog catalog, McpConnections connections) {
		return new ToolGateway(catalog, new McpToolInvoker(connections));
	}

	@Bean
	StartupToolListing startupToolListing(McpConnections connections, ToolCatalog catalog) {
		return new StartupToolListing(connections, catalog);
	}

	/**
	 * Connects to each client system once the app is up and logs the tools it
	 * offers, and which nodes may call which. A system that cannot be reached is
	 * logged, not fatal: chat, RAG and the agent do not depend on it, and its
	 * session opens on first use later. A tool the configuration names but the
	 * server does not offer is warned about: calls to it would fail.
	 */
	static class StartupToolListing {

		private final McpConnections connections;
		private final ToolCatalog catalog;

		StartupToolListing(McpConnections connections, ToolCatalog catalog) {
			this.connections = connections;
			this.catalog = catalog;
		}

		@EventListener(ApplicationReadyEvent.class)
		void listTools() {
			for (String node : catalog.nodes().stream().sorted().toList()) {
				log.info("Tool allowlist '{}': {}", node, catalog.allowed(node));
			}
			for (String name : connections.names()) {
				try {
					List<String> tools = connections.listTools(name).stream().map(McpSchema.Tool::name).sorted().toList();
					log.info("MCP connection '{}' ({}): {} tools {}", name, connections.url(name), tools.size(), tools);
					catalog.tools().stream()
							.filter(t -> t.connection().equals(name) && !tools.contains(t.remoteName()))
							.forEach(t -> log.warn("Tool '{}' is configured on '{}', but the server does not offer '{}'",
									t.name(), name, t.remoteName()));
				}
				catch (RuntimeException ex) {
					log.warn("MCP connection '{}' ({}) is not available yet: {}", name, connections.url(name),
							rootCause(ex));
				}
			}
		}

		private static String rootCause(Throwable ex) {
			Throwable t = ex;
			while (t.getCause() != null && t.getCause() != t) {
				t = t.getCause();
			}
			return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
		}
	}
}

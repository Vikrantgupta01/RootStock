package com.rootstock.runtime.tools;

import com.rootstock.autoconfig.mcp.McpConnections;
import com.rootstock.core.tools.ToolAccess;
import com.rootstock.core.tools.ToolCatalog;
import com.rootstock.core.tools.ToolDefinition;
import com.rootstock.runtime.observability.LangfuseLinks;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** What the Tool explorer shows: the allowlists, and each tool as its client system describes it. */
@Service
public class ToolExplorerService {

	private final ToolCatalog catalog;
	private final McpConnections connections;
	private final ObjectProvider<Tracer> tracer;
	private final LangfuseLinks langfuse;

	public ToolExplorerService(ToolCatalog catalog, McpConnections connections, ObjectProvider<Tracer> tracer,
			LangfuseLinks langfuse) {
		this.catalog = catalog;
		this.connections = connections;
		this.tracer = tracer;
		this.langfuse = langfuse;
	}

	public record Node(String name, List<String> allowed) {
	}

	/**
	 * @param description and inputSchema come live from the client system; null
	 *                    when it could not be reached ({@code available = false})
	 */
	public record Tool(String name, String connection, ToolAccess access, boolean available, String description,
			Map<String, Object> inputSchema, String problem) {
	}

	public record Overview(List<Node> nodes, List<Tool> tools) {
	}

	public Overview overview() {
		List<Node> nodes = catalog.nodes().stream().sorted().map(n -> new Node(n, catalog.allowed(n))).toList();

		Map<String, Map<String, McpSchema.Tool>> offered = new HashMap<>();
		Map<String, String> problems = new HashMap<>();
		for (String connection : connections.names()) {
			try {
				Map<String, McpSchema.Tool> byName = new HashMap<>();
				connections.listTools(connection).forEach(t -> byName.put(t.name(), t));
				offered.put(connection, byName);
			}
			catch (RuntimeException ex) {
				problems.put(connection, "'" + connection + "' could not be reached");
			}
		}

		List<Tool> tools = new ArrayList<>();
		for (ToolDefinition t : catalog.tools()) {
			Map<String, McpSchema.Tool> available = offered.get(t.connection());
			McpSchema.Tool remote = available == null ? null : available.get(t.remoteName());
			String problem = available == null ? problems.get(t.connection())
					: remote == null ? "'" + t.connection() + "' does not offer '" + t.remoteName() + "'" : null;
			tools.add(new Tool(t.name(), t.connection(), t.access(), remote != null,
					remote == null ? null : remote.description(), remote == null ? null : remote.inputSchema(), problem));
		}
		return new Overview(nodes, tools);
	}

	/** The current trace, so the screen can link to it; null when tracing is off. */
	public String currentTraceId() {
		Tracer t = tracer.getIfAvailable();
		Span span = t == null ? null : t.currentSpan();
		return span == null ? null : span.context().traceId();
	}

	public String traceUrl(String traceId) {
		return langfuse.traceUrl(traceId);
	}
}

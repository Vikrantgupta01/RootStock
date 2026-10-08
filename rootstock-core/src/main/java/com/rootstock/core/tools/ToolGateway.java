package com.rootstock.core.tools;

import com.rootstock.core.tools.ToolCallResult.Status;
import java.util.Map;

/**
 * The single path from Rootstock into a client system. Every tool call goes
 * through {@link #call}, which checks, in order, that the tool exists and that
 * the calling node may use it, and only then invokes it. A refused call never
 * reaches the client system.
 *
 * <p>Untrusted text (notes, documents, tool output) never changes what is
 * allowed: the allowlist is configuration, fixed at startup.
 */
public class ToolGateway {

	private final ToolCatalog catalog;
	private final ToolInvoker invoker;

	public ToolGateway(ToolCatalog catalog, ToolInvoker invoker) {
		this.catalog = catalog;
		this.invoker = invoker;
	}

	public ToolCatalog catalog() {
		return catalog;
	}

	/**
	 * @param node      the graph node (or other caller) asking, e.g. {@code enrich}
	 * @param tool      the logical tool name
	 * @param arguments the tool's input
	 */
	public ToolCallResult call(String node, String tool, Map<String, Object> arguments, ToolCallContext context) {
		long start = System.nanoTime();
		ToolDefinition definition = catalog.tool(tool).orElse(null);
		if (definition == null) {
			return result(Status.UNKNOWN_TOOL, node, tool, null, null, "There is no tool named '" + tool + "'.", start);
		}
		if (!catalog.allows(node, tool)) {
			return result(Status.BLOCKED, node, tool, definition.connection(), null, "Tool '" + tool
					+ "' is not allowed in node '" + node + "'. Allowed there: " + catalog.allowed(node) + ".", start);
		}
		try {
			String output = invoker.invoke(definition, arguments == null ? Map.of() : arguments,
					context == null ? ToolCallContext.NONE : context);
			return result(Status.OK, node, tool, definition.connection(), output, null, start);
		}
		catch (ToolInvoker.ToolErrorException ex) {
			return result(Status.TOOL_ERROR, node, tool, definition.connection(), null, ex.getMessage(), start);
		}
		catch (RuntimeException ex) {
			return result(Status.UNAVAILABLE, node, tool, definition.connection(), null,
					"Could not reach '" + definition.connection() + "': " + rootCause(ex), start);
		}
	}

	private static ToolCallResult result(Status status, String node, String tool, String connection, String output,
			String message, long start) {
		return new ToolCallResult(status, node, tool, connection, output, message,
				(System.nanoTime() - start) / 1_000_000);
	}

	private static String rootCause(Throwable ex) {
		Throwable t = ex;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
	}
}

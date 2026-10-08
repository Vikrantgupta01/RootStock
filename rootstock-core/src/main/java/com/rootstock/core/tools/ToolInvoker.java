package com.rootstock.core.tools;

import java.util.Map;

/**
 * Actually calls a tool on its client system. Implemented outside core (over
 * MCP, in autoconfig); the gateway decides whether a call may happen, an invoker
 * only how.
 */
public interface ToolInvoker {

	/**
	 * @return the tool's text output on success
	 * @throws ToolErrorException when the client system answered with an error
	 * @throws RuntimeException   for anything else: unreachable, timed out, refused at the door
	 */
	String invoke(ToolDefinition tool, Map<String, Object> arguments, ToolCallContext context);

	/** The client system ran the call and said no. Distinct from not reaching it at all. */
	class ToolErrorException extends RuntimeException {

		public ToolErrorException(String message) {
			super(message);
		}
	}
}

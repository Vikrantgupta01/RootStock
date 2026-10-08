package com.rootstock.core.tools;

/**
 * What came of one tool call through the gateway. Always a value, never an
 * exception: a graph step decides what a failure means (park the case, ask a
 * person) rather than being unwound by one.
 *
 * @param output  the tool's text result when {@link Status#OK}; otherwise null
 * @param message why it did not succeed, in words a reviewer can read; null when OK
 */
public record ToolCallResult(Status status, String node, String tool, String connection, String output,
		String message, long durationMillis) {

	public enum Status {
		/** The tool ran and returned a result. */
		OK,
		/** Refused by the gateway: the tool is not on this node's allowlist. Nothing was sent. */
		BLOCKED,
		/** No tool by that logical name. Nothing was sent. */
		UNKNOWN_TOOL,
		/** The client system answered with an error (bad input, unknown reference, access denied). */
		TOOL_ERROR,
		/** The client system could not be reached, or did not answer in time. */
		UNAVAILABLE
	}

	public boolean ok() {
		return status == Status.OK;
	}
}

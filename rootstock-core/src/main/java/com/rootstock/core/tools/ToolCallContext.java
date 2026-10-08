package com.rootstock.core.tools;

/**
 * Who a tool call is for, carried for tracing and for the client system's own
 * audit. Neither value grants anything: permission comes from the node's
 * allowlist and the token's scope.
 *
 * @param caseId     the case being worked on, if any
 * @param actingUser the person on whose behalf the agent acts, if any
 */
public record ToolCallContext(String caseId, String actingUser) {

	public static final ToolCallContext NONE = new ToolCallContext(null, null);
}

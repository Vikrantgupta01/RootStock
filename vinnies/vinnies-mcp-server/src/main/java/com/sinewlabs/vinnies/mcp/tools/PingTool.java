package com.sinewlabs.vinnies.mcp.tools;

import java.time.Clock;
import java.time.Instant;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.stereotype.Component;

/**
 * A connectivity check. Lets a client confirm it reached this server, and which
 * one, before it relies on any real tool.
 */
@Component
public class PingTool {

	private final Clock clock;

	public PingTool(Clock clock) {
		this.clock = clock;
	}

	public record Pong(String message, String server, Instant serverTime) {
	}

	@McpTool(name = "ping",
			description = "Checks that the Vinnies server is reachable. Returns 'pong', the server name and "
					+ "the server's current time (UTC). Takes no input and changes nothing.",
			annotations = @McpAnnotations(title = "Ping", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public Pong ping() {
		return new Pong("pong", "vinnies-mcp-server", clock.instant());
	}
}

package com.sinewlabs.vinnies.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PingToolTest {

	@Test
	void answersPongWithServerNameAndTime() {
		Instant now = Instant.parse("2026-10-06T12:00:00Z");
		PingTool tool = new PingTool(Clock.fixed(now, ZoneOffset.UTC));

		assertThat(tool.ping()).isEqualTo(new PingTool.Pong("pong", "vinnies-mcp-server", now));
	}
}

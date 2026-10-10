package com.rootstock.runtime.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.rootstock.autoconfig.mcp.McpConnections;
import com.rootstock.core.auth.AuthProperties;
import com.rootstock.runtime.observability.LangfuseLinks;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class PlatformStatusServiceTest {

	private final ChatModel chat = mock(ChatModel.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final LangfuseLinks langfuse = mock(LangfuseLinks.class);
	private final McpConnections mcp = mock(McpConnections.class);
	private final AuthProperties auth = new AuthProperties(new AuthProperties.Cognito("us-east-1", "pool-1", "client"));

	private PlatformStatusService service(Map<?, ?> jwks) {
		return new PlatformStatusService(new StaticListableBeanFactory(Map.of("chat", chat)).getBeanProvider(ChatModel.class),
				jdbc, auth, langfuse, mcp, url -> jwks, Duration.ofMillis(500));
	}

	private void allHealthy() {
		given(chat.call(any(Prompt.class))).willReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("OK")))));
		given(jdbc.queryForObject("SELECT 1", Integer.class)).willReturn(1);
		given(jdbc.queryForObject(any(String.class), eq(String.class))).willReturn("9");
		given(langfuse.configured()).willReturn(true);
		given(langfuse.latestTraceId()).willReturn(Optional.of("trace-1"));
		given(langfuse.traceUrl("trace-1")).willReturn("https://langfuse.example/project/p/traces/trace-1");
		given(mcp.names()).willReturn(Set.of("client"));
		given(mcp.url("client")).willReturn("http://localhost:8081/mcp");
		given(mcp.listTools("client")).willReturn(List.of(McpSchema.Tool.builder().name("find_household").build()));
	}

	@Test
	void everythingAnsweringIsAllGreenWithTheLatestTraceLinked() {
		allHealthy();

		PlatformStatusService.Status status = service(Map.of("keys", List.of(Map.of(), Map.of()))).check();

		assertThat(status.allOk()).isTrue();
		assertThat(status.checks()).extracting(PlatformStatusService.Check::name)
				.containsExactly("Bedrock", "RDS", "Cognito", "Langfuse", "MCP: client");
		assertThat(status.checks().get(3).link()).isEqualTo("https://langfuse.example/project/p/traces/trace-1");
		assertThat(status.checks().get(1).detail()).contains("V9");
		assertThat(status.checks().get(4).detail()).contains("find_household");
	}

	@Test
	void aFailingServiceIsRedWithItsReasonAndTheOthersStillReport() {
		allHealthy();
		given(chat.call(any(Prompt.class))).willThrow(new IllegalStateException("AccessDeniedException: model not enabled"));

		PlatformStatusService.Status status = service(Map.of("keys", List.of(Map.of()))).check();

		assertThat(status.allOk()).isFalse();
		assertThat(status.checks().get(0).ok()).isFalse();
		assertThat(status.checks().get(0).detail()).contains("model not enabled");
		assertThat(status.checks().subList(1, 5)).allMatch(PlatformStatusService.Check::ok);
	}

	@Test
	void aSlowServiceTimesOutInsteadOfHangingThePage() {
		allHealthy();
		given(jdbc.queryForObject("SELECT 1", Integer.class)).willAnswer(i -> {
			Thread.sleep(5_000);
			return 1;
		});

		long start = System.nanoTime();
		PlatformStatusService.Status status = service(Map.of("keys", List.of(Map.of()))).check();

		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_000);
		assertThat(status.checks().get(1).ok()).isFalse();
		assertThat(status.checks().get(1).detail()).startsWith("No answer within");
	}

	@Test
	void langfuseNotConfiguredIsRedNotSilentlyGreen() {
		allHealthy();
		given(langfuse.configured()).willReturn(false);

		PlatformStatusService.Status status = service(Map.of("keys", List.of(Map.of()))).check();

		assertThat(status.checks().get(3).ok()).isFalse();
		assertThat(status.checks().get(3).detail()).contains("not configured");
	}
}

package com.rootstock.autoconfig.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootstock.core.tools.ToolCatalog;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

/** A client system that is down must not take Rootstock down with it. */
class McpConnectionsTest {

	// Port 9 (discard) is closed on a normal machine: the connection is refused at once.
	private final ToolsProperties.Connection unreachable = new ToolsProperties.Connection("http://localhost:9", "/mcp",
			"https://auth.example/oauth2/token", "client", "secret", "demo/read", "demo/write", Duration.ofSeconds(2));

	private final ClientCredentialsTokens tokens = new ClientCredentialsTokens(Map.of("demo", unreachable),
			request -> OAuth2AccessTokenResponse.withToken("t").tokenType(OAuth2AccessToken.TokenType.BEARER)
					.expiresIn(3600).build());

	private final McpConnections connections = new McpConnections(Map.of("demo", unreachable), tokens);

	@Test
	void anUnreachableServerIsAnErrorOnUseNotACrash() {
		assertThatThrownBy(() -> connections.listTools("demo")).isInstanceOf(RuntimeException.class);
	}

	@Test
	void theStartupListingLogsInsteadOfFailing() {
		assertThatCode(() -> new McpConfiguration.StartupToolListing(connections, ToolCatalog.empty()).listTools())
				.doesNotThrowAnyException();
	}

	@Test
	void anUnknownConnectionNamesTheConfiguredOnes() {
		assertThatThrownBy(() -> connections.listTools("other")).hasMessageContaining("[demo]");
	}
}

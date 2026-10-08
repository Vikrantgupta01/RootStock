package com.rootstock.autoconfig.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootstock.core.tools.ToolAccess;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;

class ClientCredentialsTokensTest {

	private final List<Set<String>> requestedScopes = new ArrayList<>();
	private final AtomicInteger issued = new AtomicInteger();

	/** Stands in for Cognito: records what was asked for, issues a numbered token. */
	private OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenEndpoint(Duration lifetime) {
		return request -> {
			requestedScopes.add(request.getClientRegistration().getScopes());
			return OAuth2AccessTokenResponse.withToken("token-" + issued.incrementAndGet())
					.tokenType(OAuth2AccessToken.TokenType.BEARER)
					.expiresIn(lifetime.toSeconds())
					.scopes(request.getClientRegistration().getScopes())
					.build();
		};
	}

	private static ToolsProperties.Connection connection() {
		return new ToolsProperties.Connection("http://localhost:8081", "/mcp", "https://auth.example/oauth2/token",
				"client", "secret", "demo/read", "demo/write", Duration.ofSeconds(20));
	}

	@Test
	void reusesATokenUntilItIsAboutToExpire() {
		var tokens = new ClientCredentialsTokens(Map.of("demo", connection()), tokenEndpoint(Duration.ofHours(1)));

		String first = tokens.token("demo", ToolAccess.READ);
		String second = tokens.token("demo", ToolAccess.READ);

		assertThat(second).isEqualTo(first);
		assertThat(issued).hasValue(1);
	}

	@Test
	void fetchesAgainWhenTheTokenIsWithinTheRefreshMargin() {
		Duration shorterThanMargin = ClientCredentialsTokens.REFRESH_BEFORE_EXPIRY.minusSeconds(10);
		var tokens = new ClientCredentialsTokens(Map.of("demo", connection()), tokenEndpoint(shorterThanMargin));

		tokens.token("demo", ToolAccess.READ);
		tokens.token("demo", ToolAccess.READ);

		assertThat(issued).hasValue(2);
	}

	@Test
	void readAndWriteAreSeparateTokensWithTheirOwnScope() {
		var tokens = new ClientCredentialsTokens(Map.of("demo", connection()), tokenEndpoint(Duration.ofHours(1)));

		String read = tokens.token("demo", ToolAccess.READ);
		assertThat(requestedScopes).containsExactly(Set.of("demo/read"));   // no write token yet

		String write = tokens.token("demo", ToolAccess.WRITE);
		assertThat(write).isNotEqualTo(read);
		assertThat(requestedScopes).containsExactly(Set.of("demo/read"), Set.of("demo/write"));
	}

	@Test
	void aConnectionMissingASettingFailsAtStartupNamingIt() {
		var noSecret = new ToolsProperties.Connection("http://localhost:8081", "/mcp",
				"https://auth.example/oauth2/token", "client", " ", "demo/read", "demo/write", Duration.ofSeconds(20));

		assertThatThrownBy(() -> new ClientCredentialsTokens(Map.of("demo", noSecret), null))
				.hasMessageContaining("rootstock.tools.connections.demo.client-secret");
	}

	@Test
	void withNoConnectionsThereIsNothingToAuthorize() {
		var tokens = new ClientCredentialsTokens(Map.of(), null);

		assertThatThrownBy(() -> tokens.token("demo", ToolAccess.READ))
				.hasMessageContaining("No MCP connections are configured");
	}
}

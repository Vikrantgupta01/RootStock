package com.rootstock.autoconfig.mcp;

import com.rootstock.core.tools.ToolAccess;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Access tokens for MCP connections, by OAuth2 client credentials. One token per
 * connection and access level, cached until a minute before it expires, then
 * fetched again: a busy run costs one token request an hour per scope, not one
 * per call.
 *
 * <p>Read and write are separate registrations with separate scopes, so a write
 * token exists only once something has actually asked to write.
 */
public class ClientCredentialsTokens {

	static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(1);
	private static final String PRINCIPAL = "rootstock";

	private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager;

	/**
	 * @param tokenClient how token requests are sent; null for Spring's default
	 *                    (the authorization server's token endpoint over HTTP)
	 */
	public ClientCredentialsTokens(Map<String, ToolsProperties.Connection> connections,
			OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenClient) {
		List<ClientRegistration> registrations = new ArrayList<>();
		connections.forEach((name, c) -> {
			registrations.add(registration(name, ToolAccess.READ, c, c.readScope()));
			if (c.writeScope() != null && !c.writeScope().isBlank()) {
				registrations.add(registration(name, ToolAccess.WRITE, c, c.writeScope()));
			}
		});
		if (registrations.isEmpty()) {
			this.manager = null;
			return;
		}
		var repository = new InMemoryClientRegistrationRepository(registrations);
		this.manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(repository,
				new InMemoryOAuth2AuthorizedClientService(repository));
		this.manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
				.clientCredentials(cc -> {
					cc.clockSkew(REFRESH_BEFORE_EXPIRY);
					if (tokenClient != null) {
						cc.accessTokenResponseClient(tokenClient);
					}
				})
				.build());
	}

	/** A current access token for this connection and access level; fetched only when needed. */
	public String token(String connection, ToolAccess access) {
		if (manager == null) {
			throw new IllegalStateException("No MCP connections are configured");
		}
		OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
				.withClientRegistrationId(registrationId(connection, access))
				.principal(PRINCIPAL)
				.build());
		if (client == null) {
			throw new IllegalStateException("No " + access + " token for MCP connection '" + connection + "'");
		}
		return client.getAccessToken().getTokenValue();
	}

	static String registrationId(String connection, ToolAccess access) {
		return connection + "-" + access.name().toLowerCase(Locale.ROOT);
	}

	private static ClientRegistration registration(String name, ToolAccess access, ToolsProperties.Connection c,
			String scope) {
		require(name, "token-url", c.tokenUrl());
		require(name, "client-id", c.clientId());
		require(name, "client-secret", c.clientSecret());
		require(name, access == ToolAccess.READ ? "read-scope" : "write-scope", scope);
		return ClientRegistration.withRegistrationId(registrationId(name, access))
				.clientId(c.clientId())
				.clientSecret(c.clientSecret())
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.tokenUri(c.tokenUrl())
				.scope(scope)
				.build();
	}

	private static void require(String connection, String setting, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("MCP connection '" + connection + "' has no " + setting
					+ " (rootstock.tools.connections." + connection + "." + setting + ")");
		}
	}
}

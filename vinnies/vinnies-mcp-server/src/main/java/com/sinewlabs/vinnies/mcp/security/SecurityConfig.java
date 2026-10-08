package com.sinewlabs.vinnies.mcp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call this server: anyone presenting a valid Cognito access token
 * (client credentials, Iteration 2.4). Checked on every request, before MCP sees
 * it; tools then check scopes with {@code @PreAuthorize} (see {@link Scopes}).
 *
 * <p>A token is accepted only if it is signed by the pool's keys, issued by the
 * pool, unexpired, and an access token. The last check matters: the same pool's
 * user-login ID tokens are signed with the same keys and must not open this
 * server.
 *
 * <p>Web-only: the seed and reset commands run without a web server and need none
 * of this.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableMethodSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${vinnies.auth.issuer-uri}") String issuer)
			throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.oauth2ResourceServer(oauth -> oauth
						.jwt(jwt -> { })
						// RFC 9728 metadata at /.well-known/oauth-protected-resource, which MCP
						// clients read before they have a token. Spring's default claims
						// certificate-bound tokens and names no scopes or issuer; say what is true.
						.protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(m -> m
								.resourceName("Vinnies MCP server (fictional demo)")
								.authorizationServer(issuer)
								.scope("vinnies/read")
								.scope("vinnies/write")
								.tlsClientCertificateBoundAccessTokens(false))))
				// A token-authenticated API: no sessions, no cookies, so no CSRF to defend.
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(csrf -> csrf.disable())
				.build();
	}

	/**
	 * Keys come from the pool's JWKS, fetched on first use and cached, so startup
	 * does not depend on reaching Cognito.
	 */
	@Bean
	JwtDecoder jwtDecoder(@Value("${vinnies.auth.issuer-uri}") String issuer) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json").build();
		decoder.setJwtValidator(validator(issuer));
		return decoder;
	}

	/** Expiry, issuer and token_use; shared with the tests so they check exactly what runs. */
	public static OAuth2TokenValidator<Jwt> validator(String issuer) {
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), SecurityConfig::isAccessToken);
	}

	private static OAuth2TokenValidatorResult isAccessToken(Jwt jwt) {
		return "access".equals(jwt.getClaimAsString("token_use"))
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
						"Only Cognito access tokens are accepted (token_use must be 'access')", null));
	}
}

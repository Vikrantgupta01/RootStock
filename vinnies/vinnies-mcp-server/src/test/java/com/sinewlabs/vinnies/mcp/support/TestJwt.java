package com.sinewlabs.vinnies.mcp.support;

import com.sinewlabs.vinnies.mcp.security.SecurityConfig;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Test tokens without Cognito: a local RSA key signs them, and the app is given
 * a decoder that trusts that key but applies the production validators
 * ({@link SecurityConfig#validator}), so expiry, issuer and token_use are checked
 * exactly as they are against the real pool. Import {@link Config} to use it.
 */
public final class TestJwt {

	public static final String ISSUER = "https://cognito-idp.test.example/us-east-1_TEST";

	private static final KeyPair KEYS = rsa();
	private static final KeyPair OTHER_KEYS = rsa();

	private TestJwt() {
	}

	/** A valid access token with these scopes (space separated, as Cognito writes them). */
	public static String access(String scopes) {
		return mint(KEYS, ISSUER, "access", scopes, Instant.now().plus(Duration.ofHours(1)));
	}

	/** A user-login ID token from the same pool and key: must be refused. */
	public static String idToken() {
		return mint(KEYS, ISSUER, "id", null, Instant.now().plus(Duration.ofHours(1)));
	}

	public static String expired(String scopes) {
		return mint(KEYS, ISSUER, "access", scopes, Instant.now().minus(Duration.ofMinutes(5)));
	}

	public static String fromAnotherIssuer(String scopes) {
		return mint(KEYS, "https://cognito-idp.test.example/us-east-1_OTHER", "access", scopes,
				Instant.now().plus(Duration.ofHours(1)));
	}

	/** Right issuer and claims, signed with a key the server does not trust: a forgery. */
	public static String forged(String scopes) {
		return mint(OTHER_KEYS, ISSUER, "access", scopes, Instant.now().plus(Duration.ofHours(1)));
	}

	private static String mint(KeyPair keys, String issuer, String tokenUse, String scopes, Instant expiresAt) {
		JwtEncoder encoder = NimbusJwtEncoder.withKeyPair((RSAPublicKey) keys.getPublic(),
				(RSAPrivateKey) keys.getPrivate()).build();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(issuer)
				.subject("test-client")
				.claim("client_id", "test-client")
				.claim("token_use", tokenUse)
				.issuedAt(expiresAt.minus(Duration.ofHours(1)))
				.expiresAt(expiresAt);
		if (scopes != null) {
			claims.claim("scope", scopes);
		}
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	private static KeyPair rsa() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Swaps the app's Cognito decoder for one that trusts the local test key. */
	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		@Primary
		JwtDecoder testJwtDecoder() {
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
			decoder.setJwtValidator(SecurityConfig.validator(ISSUER));
			return decoder;
		}
	}
}

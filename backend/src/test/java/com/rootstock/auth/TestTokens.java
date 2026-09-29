package com.rootstock.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Authenticates a MockMvc request as a Cognito ID token would, without a real
 * token or a network round trip: the claims below are exactly the ones
 * {@link CognitoClaimsFilter} and {@code SecurityConfig}'s authority converter
 * read, so a request built here takes the same path through the filter chain as
 * a live one.
 */
public final class TestTokens {

	private TestTokens() {
	}

	/** An ADMIN of {@code tenantId} -- sees every document in the tenant, no group filtering. */
	public static RequestPostProcessor admin(String tenantId) {
		return as(tenantId, UserRole.ADMIN, List.of());
	}

	/** A VIEWER of {@code tenantId} in {@code groups} -- query-only, group-scoped retrieval. */
	public static RequestPostProcessor viewer(String tenantId, String... groups) {
		return as(tenantId, UserRole.VIEWER, List.of(groups));
	}

	public static RequestPostProcessor as(String tenantId, UserRole role, List<String> groups) {
		return jwt()
				.jwt(builder -> {
					builder.subject("test-" + role.name().toLowerCase() + "-" + tenantId)
							.claim("token_use", "id")
							.claim("email", role.name().toLowerCase() + "@" + tenantId + ".test")
							.claim(CognitoClaimsFilter.CLAIM_TENANT_ID, tenantId)
							.claim(CognitoClaimsFilter.CLAIM_ROLE, role.name());
					// Cognito omits the claim entirely for a user in no groups; a test
					// that passed an empty list instead would not exercise that case.
					if (!groups.isEmpty()) {
						builder.claim(CognitoClaimsFilter.CLAIM_GROUPS, groups);
					}
				})
				// Mirrors SecurityConfig's converter: the role claim, not OAuth scopes,
				// is what @PreAuthorize checks.
				.authorities(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	/** The claims a {@link Jwt} needs for the filter to bind a principal, as a raw map. */
	public static Jwt jwtOf(String tenantId, UserRole role, List<String> groups) {
		Jwt.Builder builder = Jwt.withTokenValue("test-token")
				.header("alg", "RS256")
				.subject("test-" + role.name().toLowerCase())
				.claim("token_use", "id")
				.claim(CognitoClaimsFilter.CLAIM_TENANT_ID, tenantId)
				.claim(CognitoClaimsFilter.CLAIM_ROLE, role.name());
		if (!groups.isEmpty()) {
			builder.claim(CognitoClaimsFilter.CLAIM_GROUPS, groups);
		}
		return builder.build();
	}
}

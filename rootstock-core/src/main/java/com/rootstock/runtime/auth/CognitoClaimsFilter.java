package com.rootstock.runtime.auth;

import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.UserRole;
import com.rootstock.core.rag.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the verified ID token's claims to the request thread: tenant into
 * {@link TenantContext} (the contract the whole RAG subsystem already reads) and
 * role/groups into {@link AuthContext}.
 *
 * <p>Runs after Spring Security has authenticated the bearer token, so by the
 * time this sees a {@link Jwt} the signature, issuer, audience, expiry and
 * {@code token_use} have all been checked (see {@link SecurityConfig}). This
 * replaces the old {@code TenantFilter}, which trusted an {@code X-Tenant-Id}
 * header any caller could set to anything.
 */
public class CognitoClaimsFilter extends OncePerRequestFilter {

	public static final String CLAIM_TENANT_ID = "custom:tenant_id";
	public static final String CLAIM_ROLE = "custom:role";
	public static final String CLAIM_GROUPS = "cognito:groups";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Jwt jwt = currentJwt();
		boolean bound = false;
		if (jwt != null) {
			String tenantId = jwt.getClaimAsString(CLAIM_TENANT_ID);
			if (tenantId != null && !tenantId.isBlank()) {
				TenantContext.set(tenantId.trim());
				AuthContext.set(principalOf(jwt));
				bound = true;
			}
		}
		try {
			chain.doFilter(request, response);
		}
		finally {
			if (bound) {
				AuthContext.clear();
				TenantContext.clear();
			}
		}
	}

	private static AuthContext.Principal principalOf(Jwt jwt) {
		return new AuthContext.Principal(
				jwt.getSubject(),
				jwt.getClaimAsString("email"),
				UserRole.fromClaim(jwt.getClaimAsString(CLAIM_ROLE)),
				groupsOf(jwt));
	}

	/** {@code cognito:groups} is absent (not an empty array) for a user in no groups. */
	static Set<String> groupsOf(Jwt jwt) {
		List<String> groups = jwt.getClaimAsStringList(CLAIM_GROUPS);
		return groups == null ? Set.of() : new LinkedHashSet<>(groups);
	}

	private static Jwt currentJwt() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
	}
}

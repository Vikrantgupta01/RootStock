package com.rootstock.auth;

import java.util.List;
import java.util.Set;

/**
 * The authenticated caller's role and access groups for the current request,
 * bound to the thread by {@link CognitoClaimsFilter} from the verified ID token.
 *
 * <p>Deliberately mirrors {@link com.rootstock.rag.tenant.TenantContext}'s
 * ThreadLocal shape — tenant, role, and groups all come off the same token, and
 * the RAG code already reaches for {@code TenantContext} the same way.
 *
 * <p>Nothing here is ever read from the database: these values come from claims
 * Cognito signed, so trusting them needs no lookup, and re-deriving them locally
 * would only add a staler second source of truth.
 */
public final class AuthContext {

	/** The synthetic group every caller implicitly belongs to; tags tenant-wide-visible documents. */
	public static final String PUBLIC_GROUP = "__public__";

	private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

	private AuthContext() {
	}

	/**
	 * @param userId Cognito's {@code sub}
	 * @param role   {@code custom:role} — {@code ADMIN}, {@code EDITOR} or {@code VIEWER}
	 * @param groups {@code cognito:groups}; empty when the caller is in none (the
	 *               claim is absent, not an empty array, in that case)
	 */
	public record Principal(String userId, String email, UserRole role, Set<String> groups) {

		/** Groups to filter retrieval by: the caller's own, plus the tenant-wide marker. */
		public Set<String> groupsForRetrieval() {
			return Set.copyOf(concat(groups, PUBLIC_GROUP));
		}

		private static Set<String> concat(Set<String> base, String extra) {
			var all = new java.util.LinkedHashSet<>(base);
			all.add(extra);
			return all;
		}
	}

	public static void set(Principal principal) {
		CURRENT.set(principal);
	}

	public static void clear() {
		CURRENT.remove();
	}

	/** @return the current principal, or {@code null} for unauthenticated/background threads. */
	public static Principal getOrNull() {
		return CURRENT.get();
	}

	/** @throws IllegalStateException if no authenticated principal is bound. */
	public static Principal require() {
		Principal principal = CURRENT.get();
		if (principal == null) {
			throw new IllegalStateException("No authenticated principal bound to the current thread");
		}
		return principal;
	}

	/** Whether the caller may see every document in their tenant regardless of group. */
	public static boolean isAdmin() {
		Principal principal = CURRENT.get();
		return principal != null && principal.role() == UserRole.ADMIN;
	}

	/**
	 * @return the groups to scope retrieval to, or {@code null} when the caller is
	 *         an admin (no group filtering at all) or no principal is bound (a
	 *         background job, which is already tenant-scoped by its own context).
	 */
	public static List<String> retrievalGroupsOrNull() {
		Principal principal = CURRENT.get();
		if (principal == null || principal.role() == UserRole.ADMIN) {
			return null;
		}
		return List.copyOf(principal.groupsForRetrieval());
	}
}

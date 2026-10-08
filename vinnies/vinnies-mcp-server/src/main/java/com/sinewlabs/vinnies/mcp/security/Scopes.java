package com.sinewlabs.vinnies.mcp.security;

/**
 * The scopes a caller's token must carry, as {@code @PreAuthorize} expressions.
 * Defined in Cognito on resource server {@code vinnies}; Spring exposes a token's
 * {@code scope} claim as {@code SCOPE_<scope>} authorities.
 */
public final class Scopes {

	/** Read tools and the guideline resources. */
	public static final String READ = "hasAuthority('SCOPE_vinnies/read')";

	/** Write tools (from Iteration 10): requested only inside commit, after an approval. */
	public static final String WRITE = "hasAuthority('SCOPE_vinnies/write')";

	private Scopes() {
	}
}

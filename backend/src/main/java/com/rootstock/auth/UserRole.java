package com.rootstock.auth;

/**
 * What a caller is allowed to <em>do</em> (as opposed to access groups, which
 * govern what they're allowed to <em>see</em>). Carried in the
 * {@code custom:role} Cognito attribute.
 */
public enum UserRole {

	/** Manages the knowledge base: everything EDITOR can do, plus profile tuning, group and access management. Sees every document in the tenant regardless of access groups. */
	ADMIN,

	/** Uploads, re-indexes and deletes documents. Retrieval is still group-scoped. */
	EDITOR,

	/** Queries only. Retrieval is group-scoped. */
	VIEWER;

	/** Lenient parse for the claim value; unknown/absent falls back to the least privilege. */
	public static UserRole fromClaim(String value) {
		if (value == null) {
			return VIEWER;
		}
		try {
			return valueOf(value.trim().toUpperCase());
		}
		catch (IllegalArgumentException unknown) {
			return VIEWER;
		}
	}
}

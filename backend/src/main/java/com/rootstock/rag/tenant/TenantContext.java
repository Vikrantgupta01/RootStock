package com.rootstock.rag.tenant;

/**
 * Holds the current request's tenant id in a {@link ThreadLocal}. Populated by
 * {@link TenantFilter} for every HTTP request and by the ingestion poller for
 * background jobs. Real authentication will later resolve the principal to a
 * tenant and set it here instead of trusting a header.
 */
public final class TenantContext {

	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(String tenantId) {
		CURRENT.set(tenantId);
	}

	public static void clear() {
		CURRENT.remove();
	}

	/** @return the current tenant id, or {@code null} if none is bound. */
	public static String getOrNull() {
		return CURRENT.get();
	}

	/** @throws IllegalStateException if no tenant is bound to the current thread. */
	public static String require() {
		String tenantId = CURRENT.get();
		if (tenantId == null || tenantId.isBlank()) {
			throw new IllegalStateException("No tenant bound to the current thread");
		}
		return tenantId;
	}

	/** Runs {@code action} with {@code tenantId} bound, restoring the prior value afterwards. */
	public static void runAs(String tenantId, Runnable action) {
		String previous = CURRENT.get();
		CURRENT.set(tenantId);
		try {
			action.run();
		}
		finally {
			if (previous != null) {
				CURRENT.set(previous);
			}
			else {
				CURRENT.remove();
			}
		}
	}
}

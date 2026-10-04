package com.rootstock.observability;

import java.util.UUID;

/**
 * The conversation the current request belongs to, for the duration of that
 * request.
 *
 * <p>Tenant and user already live in ThreadLocals that a servlet filter manages
 * ({@code TenantContext}, {@code AuthContext}); the conversation does not,
 * because it is resolved part-way through the call rather than derived from the
 * token. This holds it so {@link IdentityObservationFilter} can stamp every
 * observation -- generations and retrievals included -- and not just the root
 * span the aspect owns.
 *
 * <p>Lives in the observability package on purpose: nothing outside it should
 * have to know tracing exists.
 */
final class TraceIdentity {

	private static final ThreadLocal<UUID> CONVERSATION = new ThreadLocal<>();

	private TraceIdentity() {
	}

	static void setConversation(UUID conversationId) {
		CONVERSATION.set(conversationId);
	}

	static UUID conversation() {
		return CONVERSATION.get();
	}

	static void clear() {
		CONVERSATION.remove();
	}
}

package com.rootstock.observability;

import com.rootstock.auth.AuthContext;
import com.rootstock.rag.tenant.TenantContext;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stamps who and what onto <em>every</em> observation, not just the root span.
 *
 * <p>Langfuse treats user and session as trace-level and reads them from the
 * root, which is enough to group traces. It is not enough to filter
 * <em>observations</em>: a query for "generations by this user" or "retrievals
 * in this session" returns nothing when only the root carries the identifiers.
 * Verified against live traces, where generations and retrievals came back with
 * an empty userId.
 *
 * <p>Applied as a filter rather than at each span's creation so there is one
 * place that knows the rule, and so spans created by Spring AI -- which this
 * application never constructs -- are covered too.
 */
@Component
@ConditionalOnProperty(prefix = "rootstock.observability.langfuse", name = "enabled")
public class IdentityObservationFilter implements ObservationFilter {

	@Override
	public Observation.Context map(Observation.Context context) {
		AuthContext.Principal principal = AuthContext.getOrNull();
		if (principal != null) {
			// The Cognito `sub`. Deliberately not the email: it identifies a user
			// for filtering and cost attribution without copying personal data
			// into a third-party system.
			add(context, LangfuseAttributes.USER_ID, principal.userId());
			add(context, LangfuseAttributes.TRACE_METADATA_PREFIX + "role",
					principal.role() == null ? null : principal.role().name());
		}
		UUID conversation = TraceIdentity.conversation();
		if (conversation != null) {
			add(context, LangfuseAttributes.SESSION_ID, conversation.toString());
		}
		add(context, LangfuseAttributes.TRACE_METADATA_PREFIX + "tenant", TenantContext.getOrNull());
		return context;
	}

	private static void add(Observation.Context context, String key, String value) {
		if (StringUtils.hasText(value)) {
			context.addHighCardinalityKeyValue(KeyValue.of(key, value));
		}
	}
}

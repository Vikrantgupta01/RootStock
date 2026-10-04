package com.rootstock.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Binds {@code rootstock.observability.langfuse.*}: where traces go and who they
 * authenticate as.
 *
 * <p>Unlike the Cognito ids in {@link com.rootstock.auth.AuthProperties}, the
 * secret key is a real credential and carries no default -- it comes from
 * {@code backend/.env}, never from the committed {@code application.yml}. Every
 * field defaults to blank rather than being required, so an environment that
 * knows nothing about Langfuse still starts: an unresolvable placeholder is the
 * one thing that would stop it.
 */
@ConfigurationProperties(prefix = "rootstock.observability.langfuse")
public record LangfuseProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("") String host,
		@DefaultValue("") String publicKey,
		@DefaultValue("") String secretKey) {

	/** The OTLP traces endpoint Langfuse ingests on. */
	public String tracesEndpoint() {
		return StringUtils.trimTrailingCharacter(host.trim(), '/') + "/api/public/otel/v1/traces";
	}

	/** Whether there is actually enough configuration to export anything. */
	public boolean isUsable() {
		return enabled && StringUtils.hasText(host)
				&& StringUtils.hasText(publicKey) && StringUtils.hasText(secretKey);
	}

	/** Names what is missing, for a single actionable warning at startup. */
	public String missingFields() {
		StringBuilder missing = new StringBuilder();
		appendIfBlank(missing, host, "LANGFUSE_HOST");
		appendIfBlank(missing, publicKey, "LANGFUSE_PUBLIC_KEY");
		appendIfBlank(missing, secretKey, "LANGFUSE_SECRET_KEY");
		return missing.toString();
	}

	private static void appendIfBlank(StringBuilder target, String value, String name) {
		if (!StringUtils.hasText(value)) {
			target.append(target.isEmpty() ? "" : ", ").append(name);
		}
	}
}

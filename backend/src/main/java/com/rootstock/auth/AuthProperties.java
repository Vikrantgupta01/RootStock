package com.rootstock.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code rootstock.auth.*}: the Cognito user pool this app authenticates
 * against. Pool and client ids aren't secrets (they're public identifiers in
 * every OIDC flow), so they carry real defaults like the Bedrock ids do.
 */
@ConfigurationProperties(prefix = "rootstock.auth")
public record AuthProperties(@DefaultValue Cognito cognito) {

	public record Cognito(
			@DefaultValue("us-east-1") String region,
			@DefaultValue("us-east-1_eJI6vUhpH") String userPoolId,
			@DefaultValue("129t4ms9c91dgdmu77rpf2r0ug") String clientId) {

		/** The OIDC issuer Cognito stamps into every token it signs for this pool. */
		public String issuer() {
			return "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
		}
	}
}

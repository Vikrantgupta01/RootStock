package com.rootstock.runtime.auth;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * Rejects any Cognito token that isn't an <em>ID</em> token for <em>this</em>
 * app client.
 *
 * <p>Both checks matter. Cognito signs access tokens with the same keys as ID
 * tokens, so a signature check alone would accept either -- and access tokens
 * carry neither {@code custom:tenant_id} nor {@code custom:role} on this pool's
 * plan, which would leave the request authenticated but tenantless. The audience
 * check keeps a valid token minted for a <em>different</em> app client in the
 * same user pool from being replayed against this API.
 */
final class CognitoIdTokenValidator implements OAuth2TokenValidator<Jwt> {

	private static final String CLAIM_TOKEN_USE = "token_use";
	private static final String ID_TOKEN = "id";

	private final String clientId;

	CognitoIdTokenValidator(String clientId) {
		this.clientId = clientId;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		if (!ID_TOKEN.equals(jwt.getClaimAsString(CLAIM_TOKEN_USE))) {
			return failure("token_use must be \"id\"; access tokens carry no tenant or role claims");
		}
		List<String> audience = jwt.getClaimAsStringList(JwtClaimNames.AUD);
		if (audience == null || !audience.contains(clientId)) {
			return failure("token was not issued for this application's Cognito app client");
		}
		return OAuth2TokenValidatorResult.success();
	}

	private static OAuth2TokenValidatorResult failure(String description) {
		return OAuth2TokenValidatorResult.failure(
				new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
	}
}

package com.rootstock.autoconfig.auth;

import com.rootstock.core.auth.AuthProperties;
import com.rootstock.runtime.auth.SecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * The Cognito control-plane client used for login and group administration.
 * Credentials come from the default AWS provider chain, same as every other AWS
 * client in this application; token <em>validation</em> needs no client at all
 * (it's a public JWKS fetch -- see {@link SecurityConfig}).
 */
@Configuration(proxyBeanMethods = false)
public class CognitoConfig {

	@Bean
	CognitoIdentityProviderClient cognitoIdentityProviderClient(AuthProperties properties) {
		return CognitoIdentityProviderClient.builder()
				.region(Region.of(properties.cognito().region()))
				.build();
	}
}

package com.rootstock.runtime.auth;

import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.AuthProperties;
import com.rootstock.core.auth.UserRole;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Turns this API into an OAuth2 resource server for the Cognito user pool
 * configured under {@code rootstock.auth.cognito}.
 *
 * <p>Every {@code /api/**} route needs a valid Cognito <em>ID</em> token except
 * the login/refresh endpoints (which are how you get one) and the actuator
 * probes. This replaces the old {@code X-Tenant-Id} header filter outright:
 * tenant, role and group membership now come from claims Cognito signed, so a
 * caller can no longer name the tenant whose knowledge base they read.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
		return http
				// Token-bearing, cookie-less API: nothing to protect against CSRF,
				// and nothing to keep in a session between requests.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.cors(Customizer.withDefaults())
				.authorizeHttpRequests(requests -> requests
						.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()
						// Liveness, polled by the frontend before anyone has signed in.
						.requestMatchers(HttpMethod.GET, "/api/health").permitAll()
						.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter())))
				// Constructed here rather than exposed as a bean: a Filter bean would
				// also be picked up by the servlet container and run a second time,
				// outside the security chain, where no Jwt is bound yet.
				.addFilterAfter(new CognitoClaimsFilter(), BearerTokenAuthenticationFilter.class)
				.build();
	}

	/**
	 * Validates against the pool's published JWKS. The issuer check pins tokens to
	 * this user pool; {@link CognitoIdTokenValidator} pins them to ID tokens for
	 * this app client.
	 */
	@Bean
	JwtDecoder jwtDecoder(AuthProperties properties) {
		AuthProperties.Cognito cognito = properties.cognito();
		NimbusJwtDecoder decoder = NimbusJwtDecoder
				.withJwkSetUri(cognito.issuer() + "/.well-known/jwks.json")
				.build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(cognito.issuer()),
				new CognitoIdTokenValidator(cognito.clientId())));
		return decoder;
	}

	/**
	 * Maps the single-valued {@code custom:role} claim onto a {@code ROLE_*}
	 * authority so {@code @PreAuthorize("hasRole('ADMIN')")} works without custom
	 * SpEL. Cognito groups are deliberately <em>not</em> mapped to authorities:
	 * they govern which documents a caller may see, not which actions they may
	 * take, and are read off the token by {@link AuthContext} at retrieval time.
	 */
	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authoritiesOf);
		return converter;
	}

	private static List<GrantedAuthority> authoritiesOf(Jwt jwt) {
		UserRole role = UserRole.fromClaim(jwt.getClaimAsString(CognitoClaimsFilter.CLAIM_ROLE));
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}
}

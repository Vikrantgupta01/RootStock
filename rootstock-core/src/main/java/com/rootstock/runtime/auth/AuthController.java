package com.rootstock.runtime.auth;

import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.CognitoService;
import com.rootstock.core.auth.dto.CurrentUserResponse;
import com.rootstock.core.auth.dto.LoginRequest;
import com.rootstock.core.auth.dto.RefreshRequest;
import com.rootstock.core.auth.dto.TokenResponse;
import com.rootstock.core.rag.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;

/**
 * Sign-in and "who am I". The login and refresh routes are the only ones in
 * {@code /api/**} reachable without a token (see {@link SecurityConfig}).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final CognitoService cognito;

	public AuthController(CognitoService cognito) {
		this.cognito = cognito;
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		AuthenticationResultType result = cognito.login(request.email().trim(), request.password());
		return new TokenResponse(result.idToken(), result.refreshToken(), result.expiresIn());
	}

	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		AuthenticationResultType result = cognito.refresh(request.refreshToken());
		return new TokenResponse(result.idToken(), null, result.expiresIn());
	}

	@GetMapping("/me")
	public CurrentUserResponse me() {
		AuthContext.Principal principal = AuthContext.require();
		return new CurrentUserResponse(
				principal.userId(),
				principal.email(),
				TenantContext.require(),
				principal.role().name(),
				principal.groups());
	}
}

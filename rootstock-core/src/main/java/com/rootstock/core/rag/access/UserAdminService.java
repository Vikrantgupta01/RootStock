package com.rootstock.core.rag.access;

import com.rootstock.core.auth.CognitoService;
import com.rootstock.core.auth.UserRole;
import com.rootstock.core.common.ResourceNotFoundException;
import com.rootstock.core.rag.tenant.TenantContext;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Creates users inside the caller's own tenant.
 *
 * <p>The tenant is never taken from the request: it comes from
 * {@link TenantContext}, which is bound from the caller's verified token. An
 * admin can therefore hand out any role, including {@code ADMIN}, but only ever
 * within the tenant they already administer -- there is no parameter that would
 * let one reach another tenant's user directory.
 *
 * <p>Bootstrapping a tenant's <em>first</em> admin is deliberately not possible
 * here: with no admin yet, nothing could authorize the call. That stays an
 * out-of-band {@code aws cognito-idp admin-create-user}, gated by IAM rather
 * than by this application.
 */
@Service
public class UserAdminService {

	private final CognitoService cognito;
	private final AccessGroupRepository groups;

	public UserAdminService(CognitoService cognito, AccessGroupRepository groups) {
		this.cognito = cognito;
		this.groups = groups;
	}

	/**
	 * @param groupNames access groups to place the new user in; each must already
	 *                   exist in this tenant
	 * @return the created user's normalized email address
	 */
	@PreAuthorize("hasRole('ADMIN')")
	public String create(String email, String password, UserRole role, List<String> groupNames) {
		String tenantId = TenantContext.require();
		// Cognito treats the email as the username for this pool, and matches it
		// case-insensitively; normalizing keeps the local group checks and anything
		// that later joins on it from disagreeing about the same person.
		String username = email.trim().toLowerCase();

		List<String> requested = groupNames.stream().map(name -> name.trim().toLowerCase()).toList();
		for (String name : requested) {
			groups.findByTenantIdAndName(tenantId, name)
					.orElseThrow(() -> ResourceNotFoundException.of("Access group", name));
		}

		cognito.createUser(username, password, tenantId, role);
		// After creation, so a rejected password never leaves a user half-configured.
		for (String name : requested) {
			cognito.addUserToGroup(username, name);
		}
		return username;
	}
}

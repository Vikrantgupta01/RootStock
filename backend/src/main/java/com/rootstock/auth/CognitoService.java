package com.rootstock.auth;

import com.rootstock.common.DuplicateResourceException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRemoveUserFromGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CreateGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupExistsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListGroupsRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * The application's only outbound calls to Cognito: exchanging credentials for
 * tokens, and administering the groups that back document ACLs.
 *
 * <p>Login is proxied through the backend rather than done in the browser so the
 * frontend never talks to AWS directly -- the same posture as S3 and Bedrock,
 * which it also only reaches through this API.
 */
@Service
public class CognitoService {

	/** A failed login says nothing about <em>why</em>: distinguishing them enumerates accounts. */
	private static final String REJECTED = "Incorrect email address or password.";

	private final CognitoIdentityProviderClient cognito;
	private final AuthProperties.Cognito properties;

	public CognitoService(CognitoIdentityProviderClient cognito, AuthProperties properties) {
		this.cognito = cognito;
		this.properties = properties.cognito();
	}

	/** Exchanges email + password for Cognito's own tokens; this app mints none of its own. */
	public AuthenticationResultType login(String email, String password) {
		return authenticate(AuthFlowType.USER_PASSWORD_AUTH,
				Map.of("USERNAME", email, "PASSWORD", password));
	}

	/**
	 * Trades a refresh token for a fresh ID token. Cognito does not return a new
	 * refresh token here -- the caller keeps the one it already has until it
	 * expires, at which point login starts over.
	 */
	public AuthenticationResultType refresh(String refreshToken) {
		return authenticate(AuthFlowType.REFRESH_TOKEN_AUTH, Map.of("REFRESH_TOKEN", refreshToken));
	}

	private AuthenticationResultType authenticate(AuthFlowType flow, Map<String, String> parameters) {
		try {
			InitiateAuthResponse response = cognito.initiateAuth(InitiateAuthRequest.builder()
					.clientId(properties.clientId())
					.authFlow(flow)
					.authParameters(parameters)
					.build());
			AuthenticationResultType result = response.authenticationResult();
			if (result == null) {
				// A challenge (NEW_PASSWORD_REQUIRED, MFA, ...). None are wired up in
				// this cut, so there is no way for the caller to answer one.
				throw new InvalidCredentialsException(
						"This account needs to complete '" + response.challengeNameAsString()
								+ "' before it can sign in.");
			}
			return result;
		}
		catch (NotAuthorizedException | UserNotFoundException | UserNotConfirmedException rejected) {
			throw new InvalidCredentialsException(REJECTED);
		}
	}

	/** Group names that exist in the pool. Membership itself is only ever read off the caller's token. */
	public List<String> listGroupNames() {
		return cognito.listGroupsPaginator(ListGroupsRequest.builder().userPoolId(properties.userPoolId()).build())
				.stream()
				.flatMap(page -> page.groups().stream())
				.map(group -> group.groupName())
				.sorted()
				.toList();
	}

	/** Creates the group in Cognito, tolerating one that is already there (this call is idempotent). */
	public void createGroup(String name, String description) {
		try {
			cognito.createGroup(CreateGroupRequest.builder()
					.userPoolId(properties.userPoolId())
					.groupName(name)
					.description(description)
					.build());
		}
		catch (GroupExistsException alreadyThere) {
			// Nothing to do -- the desired end state is exactly what already exists.
		}
	}

	/**
	 * Creates a confirmed, ready-to-sign-in user. Two calls, not one: Cognito's
	 * {@code AdminCreateUser} can only set a <em>temporary</em> password, which
	 * leaves the account in {@code FORCE_CHANGE_PASSWORD} -- and this app answers
	 * no auth challenges, so such an account could never sign in.
	 *
	 * @param role      goes into {@code custom:role}; drives every {@code @PreAuthorize} check
	 * @param tenantId  goes into {@code custom:tenant_id}; scopes everything the user can reach
	 */
	public void createUser(String email, String password, String tenantId, UserRole role) {
		try {
			cognito.adminCreateUser(AdminCreateUserRequest.builder()
					.userPoolId(properties.userPoolId())
					.username(email)
					// No invitation email: this app has no hosted UI to send anyone to,
					// and the password is handed over out of band.
					.messageAction(MessageActionType.SUPPRESS)
					.userAttributes(
							attribute("email", email),
							attribute("email_verified", "true"),
							attribute(CognitoClaimsFilter.CLAIM_TENANT_ID, tenantId),
							attribute(CognitoClaimsFilter.CLAIM_ROLE, role.name()))
					.build());
		}
		catch (UsernameExistsException exists) {
			throw new DuplicateResourceException("A user with the email address " + email + " already exists.");
		}
		catch (InvalidPasswordException | InvalidParameterException rejected) {
			// Cognito's own message names the policy rule that failed, which is more
			// useful than anything this app could restate.
			throw new IllegalArgumentException(rejected.awsErrorDetails().errorMessage());
		}

		try {
			cognito.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
					.userPoolId(properties.userPoolId())
					.username(email)
					.password(password)
					.permanent(true)
					.build());
		}
		catch (InvalidPasswordException weak) {
			// The account exists but has no usable password. Say so plainly rather
			// than reporting a generic failure for a half-created user.
			throw new IllegalArgumentException(weak.awsErrorDetails().errorMessage()
					+ " The account was created but cannot sign in until a password is set for it.");
		}
	}

	private static AttributeType attribute(String name, String value) {
		return AttributeType.builder().name(name).value(value).build();
	}

	public void addUserToGroup(String username, String groupName) {
		cognito.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
				.userPoolId(properties.userPoolId())
				.username(username)
				.groupName(groupName)
				.build());
	}

	public void removeUserFromGroup(String username, String groupName) {
		cognito.adminRemoveUserFromGroup(AdminRemoveUserFromGroupRequest.builder()
				.userPoolId(properties.userPoolId())
				.username(username)
				.groupName(groupName)
				.build());
	}
}

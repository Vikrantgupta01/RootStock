package com.rootstock.rag.access;

import com.rootstock.auth.UserRole;
import com.rootstock.rag.document.dto.DocumentDetailResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for access control: the groups documents can be restricted to,
 * which groups a document is restricted to, and the users who belong to them.
 * Authorization is enforced on the services rather than here, so it applies
 * however they are reached.
 */
@RestController
@RequestMapping("/api/rag")
public class AccessGroupController {

	private final AccessGroupService accessGroups;
	private final UserAdminService users;

	public AccessGroupController(AccessGroupService accessGroups, UserAdminService users) {
		this.accessGroups = accessGroups;
		this.users = users;
	}

	@GetMapping("/access-groups")
	public List<String> list() {
		return accessGroups.list();
	}

	@PostMapping("/access-groups")
	public CreatedGroup create(@Valid @RequestBody CreateAccessGroupRequest request) {
		return new CreatedGroup(accessGroups.create(request.name(), request.description()));
	}

	@PutMapping("/documents/{documentId}/access-groups")
	public DocumentDetailResponse setDocumentGroups(@PathVariable UUID documentId,
			@Valid @RequestBody SetAccessGroupsRequest request) {
		return accessGroups.setDocumentGroups(documentId, request.groups());
	}

	/**
	 * Creates a user in the caller's own tenant. The tenant is not a parameter --
	 * see {@link UserAdminService}.
	 */
	@PostMapping("/users")
	@ResponseStatus(HttpStatus.CREATED)
	public CreatedUser createUser(@Valid @RequestBody CreateUserRequest request) {
		String email = users.create(request.email(), request.password(), request.role(), request.groupsOrEmpty());
		return new CreatedUser(email, request.role(), request.groupsOrEmpty());
	}

	/**
	 * @param password set as a permanent password, so the new user can sign in
	 *                 straight away; it must satisfy the user pool's policy, and
	 *                 Cognito's own message says which rule failed if it doesn't
	 */
	public record CreateUserRequest(
			@Email @NotBlank @Size(max = 320) String email,
			@NotBlank @Size(min = 8, max = 256) String password,
			@NotNull UserRole role,
			List<@Size(min = 2, max = 63) String> groups) {

		List<String> groupsOrEmpty() {
			return groups == null ? List.of() : groups;
		}
	}

	public record CreatedUser(String email, UserRole role, List<String> groups) {
	}

	public record CreateAccessGroupRequest(
			@NotNull @Size(min = 2, max = 63) String name,
			@Size(max = 512) String description) {
	}

	/** An empty list means "visible to the whole tenant" -- it clears every grant. */
	public record SetAccessGroupsRequest(@NotNull List<@Size(min = 2, max = 63) String> groups) {
	}

	public record CreatedGroup(String name) {
	}
}

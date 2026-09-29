package com.rootstock.rag.access;

import com.rootstock.rag.document.dto.DocumentDetailResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for document access control. Authorization is enforced on
 * {@link AccessGroupService} rather than here, so it applies however the service
 * is reached.
 */
@RestController
@RequestMapping("/api/rag")
public class AccessGroupController {

	private final AccessGroupService accessGroups;

	public AccessGroupController(AccessGroupService accessGroups) {
		this.accessGroups = accessGroups;
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

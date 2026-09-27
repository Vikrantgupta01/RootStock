package com.rootstock.rag.profile;

import com.rootstock.rag.profile.dto.CreateRagProfileRequest;
import com.rootstock.rag.profile.dto.RagProfileResponse;
import com.rootstock.rag.profile.dto.UpdateRagProfileRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.HttpStatus.CREATED;

/** Tunable RAG profiles: named, versioned bundles of query-time settings. */
@RestController
@RequestMapping("/api/rag/profiles")
public class RagProfileController {

	private final RagProfileService service;

	public RagProfileController(RagProfileService service) {
		this.service = service;
	}

	@GetMapping
	public List<RagProfileResponse> list() {
		return service.listProfiles().stream().map(RagProfileResponse::of).toList();
	}

	@PostMapping
	@ResponseStatus(CREATED)
	public RagProfileResponse create(@Valid @RequestBody CreateRagProfileRequest request) {
		return RagProfileResponse.of(service.create(request));
	}

	@GetMapping("/{id}")
	public RagProfileResponse get(@PathVariable UUID id) {
		return RagProfileResponse.of(service.get(id));
	}

	@GetMapping("/{id}/versions")
	public List<RagProfileResponse> versions(@PathVariable UUID id) {
		return service.versionsOf(id).stream().map(RagProfileResponse::of).toList();
	}

	@PostMapping("/{id}/versions")
	@ResponseStatus(CREATED)
	public RagProfileResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRagProfileRequest request) {
		return RagProfileResponse.of(service.update(id, request));
	}

	@PostMapping("/{id}/activate")
	public RagProfileResponse activate(@PathVariable UUID id) {
		return RagProfileResponse.of(service.activate(id));
	}
}

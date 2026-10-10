package com.rootstock.core.rag.access;

import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.CognitoService;
import com.rootstock.core.common.ResourceNotFoundException;
import com.rootstock.core.rag.document.DocumentService;
import com.rootstock.core.rag.document.dto.DocumentDetailResponse;
import com.rootstock.core.rag.tenant.TenantContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the groups documents can be restricted to, and which groups a given
 * document is restricted to.
 *
 * <p>Creating a group writes it in two places: Cognito (which owns membership,
 * and is what a caller's token will name) and the local {@code access_group}
 * mirror (which the document join table points at). Cognito's groups are
 * pool-wide rather than per-tenant, so two tenants asking for the same group
 * name share one Cognito group -- harmless, because every retrieval filter
 * still ANDs the tenant in, so a member of tenant A's {@code hr-only} can never
 * see tenant B's documents whatever their group membership says.
 */
@Service
public class AccessGroupService {

	/** Cognito allows a broad character set; this is the stricter subset worth exposing. */
	private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9-_]{1,62}");

	private final AccessGroupRepository groups;
	private final CognitoService cognito;
	private final DocumentService documents;

	public AccessGroupService(AccessGroupRepository groups, CognitoService cognito, DocumentService documents) {
		this.groups = groups;
		this.cognito = cognito;
		this.documents = documents;
	}

	/** Groups available to tag a document with, for this tenant. */
	@Transactional(readOnly = true)
	@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
	public List<String> list() {
		return groups.findByTenantIdOrderByNameAsc(TenantContext.require()).stream()
				.map(AccessGroup::getName)
				.toList();
	}

	/**
	 * Creates the group in Cognito and mirrors it locally. Idempotent in both
	 * places, so re-creating an existing group is a no-op rather than an error.
	 */
	@Transactional
	@PreAuthorize("hasRole('ADMIN')")
	public String create(String name, String description) {
		String tenantId = TenantContext.require();
		String normalized = requireValidName(name);
		cognito.createGroup(normalized, description);
		groups.findByTenantIdAndName(tenantId, normalized)
				.orElseGet(() -> groups.save(new AccessGroup(tenantId, normalized)));
		return normalized;
	}

	/** Replaces a document's grants. An empty list restores tenant-wide visibility. */
	@Transactional
	@PreAuthorize("hasRole('ADMIN')")
	public DocumentDetailResponse setDocumentGroups(UUID documentId, List<String> groupNames) {
		String tenantId = TenantContext.require();
		Set<AccessGroup> resolved = new LinkedHashSet<>();
		for (String name : groupNames) {
			resolved.add(groups.findByTenantIdAndName(tenantId, name.trim().toLowerCase())
					.orElseThrow(() -> ResourceNotFoundException.of("Access group", name)));
		}
		return documents.replaceAccessGroups(documentId, resolved);
	}

	private static String requireValidName(String name) {
		String normalized = name == null ? "" : name.trim().toLowerCase();
		if (!VALID_NAME.matcher(normalized).matches()) {
			throw new IllegalArgumentException(
					"Group names are 2-63 characters of lowercase letters, digits, '-' or '_', "
							+ "and must start with a letter or digit.");
		}
		if (AuthContext.PUBLIC_GROUP.equals(normalized)) {
			throw new IllegalArgumentException(
					"'" + AuthContext.PUBLIC_GROUP + "' is reserved for documents visible to the whole tenant.");
		}
		return normalized;
	}
}

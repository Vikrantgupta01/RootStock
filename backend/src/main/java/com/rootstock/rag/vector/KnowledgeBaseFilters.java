package com.rootstock.rag.vector;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentruntime.model.FilterAttribute;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;

/**
 * Builds Bedrock Retrieve API filter expressions over the metadata attributes
 * every document carries (see {@link RagChunkMetadata}, set via the S3
 * {@code .metadata.json} sidecar at ingest time).
 *
 * <p>These filters are applied by Bedrock <em>server-side</em>, before anything
 * is returned and long before any chunk reaches the chat model -- which is what
 * makes them an access-control boundary rather than a post-filter.
 */
public final class KnowledgeBaseFilters {

	private KnowledgeBaseFilters() {
	}

	/**
	 * {@code tenant_id = ? AND document_version_id IN (?) AND access_groups CONTAINS ANY (?)}
	 * -- the chunks a given caller may retrieve over.
	 *
	 * @param callerGroups the caller's access groups (already including the
	 *                     tenant-wide marker), or {@code null} to skip group
	 *                     filtering entirely -- an admin, or a background job that
	 *                     is already scoped by tenant
	 */
	public static RetrievalFilter retrieval(String tenantId, List<String> activeVersionIds, List<String> callerGroups) {
		List<RetrievalFilter> clauses = new ArrayList<>();
		clauses.add(tenant(tenantId));
		if (!activeVersionIds.isEmpty()) {
			clauses.add(RetrievalFilter.builder()
					.in(FilterAttribute.builder()
							.key(RagChunkMetadata.DOCUMENT_VERSION_ID)
							.value(Document.fromList(activeVersionIds.stream().map(Document::fromString).toList()))
							.build())
					.build());
		}
		if (callerGroups != null) {
			clauses.add(anyGroup(callerGroups));
		}
		return clauses.size() == 1 ? clauses.get(0) : RetrievalFilter.builder().andAll(clauses).build();
	}

	/** {@code tenant_id = ?} on its own. */
	public static RetrievalFilter tenant(String tenantId) {
		return RetrievalFilter.builder()
				.equalsValue(FilterAttribute.builder()
						.key(RagChunkMetadata.TENANT_ID)
						.value(Document.fromString(tenantId))
						.build())
				.build();
	}

	/**
	 * Matches a document whose {@code access_groups} list contains at least one of
	 * {@code groups}. {@code listContains} tests one member at a time -- there is no
	 * "intersects" operator -- so a caller in several groups becomes an {@code orAll}
	 * of one clause each.
	 */
	private static RetrievalFilter anyGroup(List<String> groups) {
		List<RetrievalFilter> clauses = groups.stream()
				.map(group -> RetrievalFilter.builder()
						.listContains(FilterAttribute.builder()
								.key(RagChunkMetadata.ACCESS_GROUPS)
								.value(Document.fromString(group))
								.build())
						.build())
				.toList();
		// orAll rejects a single-element list, and a lone clause needs no wrapper.
		return clauses.size() == 1 ? clauses.get(0) : RetrievalFilter.builder().orAll(clauses).build();
	}
}

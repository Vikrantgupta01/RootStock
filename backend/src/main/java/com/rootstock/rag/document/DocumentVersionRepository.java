package com.rootstock.rag.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

	List<DocumentVersion> findByDocumentIdOrderByVersionNoDesc(UUID documentId);

	Optional<DocumentVersion> findByDocumentIdAndVersionNo(UUID documentId, int versionNo);

	Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNoDesc(UUID documentId);

	Optional<DocumentVersion> findByTenantIdAndId(String tenantId, UUID id);

	List<DocumentVersion> findByTenantIdAndIdIn(String tenantId, List<UUID> ids);
}

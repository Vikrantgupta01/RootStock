package com.rootstock.rag.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

	Optional<Document> findByTenantIdAndId(String tenantId, UUID id);

	Optional<Document> findByTenantIdAndSourceKey(String tenantId, String sourceKey);

	Page<Document> findByTenantId(String tenantId, Pageable pageable);

	List<Document> findByTenantId(String tenantId);

	List<Document> findByTenantIdAndIdIn(String tenantId, List<UUID> ids);
}

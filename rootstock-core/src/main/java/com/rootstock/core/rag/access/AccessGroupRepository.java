package com.rootstock.core.rag.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessGroupRepository extends JpaRepository<AccessGroup, UUID> {

	List<AccessGroup> findByTenantIdOrderByNameAsc(String tenantId);

	Optional<AccessGroup> findByTenantIdAndName(String tenantId, String name);

	List<AccessGroup> findByTenantIdAndNameIn(String tenantId, List<String> names);
}

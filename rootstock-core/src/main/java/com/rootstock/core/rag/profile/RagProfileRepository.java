package com.rootstock.core.rag.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagProfileRepository extends JpaRepository<RagProfile, UUID> {

	Optional<RagProfile> findByTenantIdAndActiveTrue(String tenantId);

	Optional<RagProfile> findByTenantIdAndId(String tenantId, UUID id);

	List<RagProfile> findByTenantIdOrderByNameAscVersionNoDesc(String tenantId);

	List<RagProfile> findByTenantIdAndNameOrderByVersionNoDesc(String tenantId, String name);

	Optional<RagProfile> findFirstByTenantIdAndNameOrderByVersionNoDesc(String tenantId, String name);
}

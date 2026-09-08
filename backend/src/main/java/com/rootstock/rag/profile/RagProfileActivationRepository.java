package com.rootstock.rag.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagProfileActivationRepository extends JpaRepository<RagProfileActivation, UUID> {

	Optional<RagProfileActivation> findByTenantIdAndState(String tenantId, RagProfileActivation.State state);

	List<RagProfileActivation> findByState(RagProfileActivation.State state);
}

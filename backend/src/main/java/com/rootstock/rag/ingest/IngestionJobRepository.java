package com.rootstock.rag.ingest;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

	/**
	 * Claim up to {@code limit} queued jobs, skipping rows already locked by
	 * another worker. Must be called inside a transaction; the caller flips the
	 * returned rows to {@code RUNNING} before the transaction commits.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("""
			select j from IngestionJob j
			where j.state = com.rootstock.rag.ingest.IngestionJobState.QUEUED
			order by j.createdAt asc
			""")
	List<IngestionJob> lockQueued(Pageable pageable);

	Page<IngestionJob> findByTenantId(String tenantId, Pageable pageable);

	Page<IngestionJob> findByTenantIdAndState(String tenantId, IngestionJobState state, Pageable pageable);

	java.util.Optional<IngestionJob> findByTenantIdAndId(String tenantId, UUID id);

	long countByStateIn(List<IngestionJobState> states);

	long countByProfileIdAndKind(UUID profileId, IngestionJobKind kind);

	long countByProfileIdAndKindAndStateIn(UUID profileId, IngestionJobKind kind, List<IngestionJobState> states);
}

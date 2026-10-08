package com.sinewlabs.vinnies.mcp.assistance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AssistanceRepository extends JpaRepository<Assistance, UUID> {

	/** A household's assistance on or after {@code from}, newest first. */
	@Query("""
			select a from Assistance a
			where a.household.id = :householdId and a.assistedOn >= :from
			order by a.assistedOn desc, a.ref desc
			""")
	List<Assistance> findSince(UUID householdId, LocalDate from);
}

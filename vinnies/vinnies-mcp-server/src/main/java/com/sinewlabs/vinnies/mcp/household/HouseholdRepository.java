package com.sinewlabs.vinnies.mcp.household;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {

	Optional<Household> findByRef(String ref);

	/** Every household with its members in one query, for matching. */
	@Query("select distinct h from Household h left join fetch h.members")
	List<Household> findAllWithMembers();
}

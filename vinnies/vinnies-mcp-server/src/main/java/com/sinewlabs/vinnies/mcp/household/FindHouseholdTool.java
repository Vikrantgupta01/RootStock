package com.sinewlabs.vinnies.mcp.household;

import com.sinewlabs.vinnies.mcp.household.HouseholdMatcher.HouseholdMatch;
import com.sinewlabs.vinnies.mcp.security.Scopes;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * find_household: match a household by name and suburb, optionally phone
 * (tool catalogue: Read, scope vinnies/read from Iteration 2).
 *
 * <p>Scores every household in memory. Fine for the demo's 50; a real client
 * system would narrow candidates in the database first (by suburb or a trigram
 * index) and score only those.
 */
@Component
public class FindHouseholdTool {

	private final HouseholdRepository households;

	public FindHouseholdTool(HouseholdRepository households) {
		this.households = households;
	}

	public record HouseholdMatches(List<HouseholdMatch> matches) {
	}

	@McpTool(name = "find_household",
			description = "Find a household by name and suburb, and phone if known. Returns up to 5 candidate "
					+ "households ranked by matchScore from 0 to 1 (1 = certain); an exact phone match scores 1. "
					+ "Names may be misspelt or partial. Use the chosen candidate's householdRef in other tools. "
					+ "An empty list means no household is a likely match. Returns no contact details.",
			annotations = @McpAnnotations(title = "Find household", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	@PreAuthorize(Scopes.READ)
	@Transactional(readOnly = true)
	public HouseholdMatches findHousehold(
			@McpToolParam(description = "Name of a household member or the family name, e.g. 'Linh Tran' or "
					+ "'Tran'. Spelling may be approximate.") String name,
			@McpToolParam(description = "Suburb the household lives in, e.g. 'Blacktown'.") String suburb,
			@McpToolParam(required = false, description = "Phone number if known, in any format, e.g. "
					+ "'02 5550 0101'.") String phone) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		if (suburb == null || suburb.isBlank()) {
			throw new IllegalArgumentException("suburb is required");
		}
		return new HouseholdMatches(HouseholdMatcher.rank(households.findAllWithMembers(), name, suburb, phone));
	}
}

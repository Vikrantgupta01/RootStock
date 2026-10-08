package com.sinewlabs.vinnies.mcp.assistance;

import com.sinewlabs.vinnies.mcp.household.Household;
import com.sinewlabs.vinnies.mcp.household.HouseholdRepository;
import com.sinewlabs.vinnies.mcp.security.Scopes;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * get_assistance_history: what a household has already been given (tool
 * catalogue: Read, returns date, type, amount). Feeds the frequency rule (R03)
 * and reviewers.
 *
 * <p>Returns only date, type and amount: no notes, names or contact details.
 */
@Component
public class AssistanceHistoryTool {

	static final int MAX_DAYS = 3650;

	private final HouseholdRepository households;
	private final AssistanceRepository assistance;
	private final Clock clock;

	public AssistanceHistoryTool(HouseholdRepository households, AssistanceRepository assistance, Clock clock) {
		this.households = households;
		this.assistance = assistance;
		this.clock = clock;
	}

	public record AssistanceEntry(LocalDate date, NeedCategory type, BigDecimal amountAud) {
	}

	/** The window is inclusive at both ends: {@code from} = today minus sinceDays. */
	public record AssistanceHistory(String householdRef, LocalDate from, LocalDate to,
			List<AssistanceEntry> assistance) {
	}

	@McpTool(name = "get_assistance_history",
			description = "Past assistance given to one household within the last N days, newest first: date, "
					+ "type (FOOD, ENERGY_BILL or RENT) and amount in AUD. Use the householdRef returned by "
					+ "find_household. An empty list means no assistance in that window; an unknown "
					+ "householdRef is an error. Dates are Sydney dates.",
			annotations = @McpAnnotations(title = "Get assistance history", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	@PreAuthorize(Scopes.READ)
	@Transactional(readOnly = true)
	public AssistanceHistory getAssistanceHistory(
			@McpToolParam(description = "Household reference from find_household, e.g. 'HH-0001'.")
			String householdRef,
			@McpToolParam(description = "Look-back window in days, 1 to 3650, e.g. 90 for the last three months.")
			Integer sinceDays) {
		if (householdRef == null || householdRef.isBlank()) {
			throw new IllegalArgumentException("householdRef is required");
		}
		if (sinceDays == null || sinceDays < 1 || sinceDays > MAX_DAYS) {
			throw new IllegalArgumentException("sinceDays must be between 1 and " + MAX_DAYS);
		}
		String ref = householdRef.strip().toUpperCase();
		Household household = households.findByRef(ref)
				.orElseThrow(() -> new IllegalArgumentException("No household with ref " + ref));

		LocalDate today = LocalDate.now(clock);
		LocalDate from = today.minusDays(sinceDays);
		List<AssistanceEntry> entries = assistance.findSince(household.getId(), from).stream()
				.map(a -> new AssistanceEntry(a.getAssistedOn(), a.getCategory(), a.getAmountAud()))
				.toList();
		return new AssistanceHistory(ref, from, today, entries);
	}
}

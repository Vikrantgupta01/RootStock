package com.sinewlabs.vinnies.mcp.demodata;

import com.sinewlabs.vinnies.mcp.household.Relationship;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The complete fictional data set, as plain values. Records rather than JPA
 * entities so two generations can be compared with {@code equals}: that is how
 * the tests prove the seed is repeatable.
 */
public record DemoData(List<HouseholdRow> households, List<AssistanceRow> assistance, List<ServiceRow> services,
		List<GuidelineRow> guidelines) {

	public record HouseholdRow(UUID id, String ref, String familyName, String suburb, String postcode, String phone,
			boolean consentGiven, Instant createdAt, List<PersonRow> members) {
	}

	public record PersonRow(UUID id, String givenName, String familyName, Relationship relationship,
			Integer birthYear) {
	}

	public record AssistanceRow(UUID id, String ref, UUID householdId, LocalDate assistedOn, NeedCategory category,
			BigDecimal amountAud, String description) {
	}

	public record ServiceRow(UUID id, String ref, String name, NeedCategory needCategory, String suburb,
			String postcode, String address, String phone, String hours, String eligibility) {
	}

	public record GuidelineRow(NeedCategory assistanceType, String title, String guidelineText,
			BigDecimal limitPerVisitAud, int repeatWindowDays, LocalDate effectiveFrom) {
	}

	public int personCount() {
		return households.stream().mapToInt(h -> h.members().size()).sum();
	}
}

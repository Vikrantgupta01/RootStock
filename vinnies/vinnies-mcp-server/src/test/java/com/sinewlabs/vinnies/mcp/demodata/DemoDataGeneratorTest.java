package com.sinewlabs.vinnies.mcp.demodata;

import static org.assertj.core.api.Assertions.assertThat;

import com.sinewlabs.vinnies.mcp.demodata.DemoData.HouseholdRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoData.PersonRow;
import com.sinewlabs.vinnies.mcp.household.Relationship;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DemoDataGeneratorTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

	private final DemoData data = DemoDataGenerator.generate(TODAY);

	@Test
	void sameDayGivesIdenticalData() {
		assertThat(DemoDataGenerator.generate(TODAY)).isEqualTo(data);
	}

	@Test
	void hasTheDesignedVolumes() {
		assertThat(data.households()).hasSize(50);
		assertThat(data.assistance()).hasSize(200);
		assertThat(data.services()).hasSize(40);
	}

	@Test
	void refsAndIdsAreUnique() {
		assertUnique(data.households().stream().map(HouseholdRow::ref));
		assertUnique(data.households().stream().map(HouseholdRow::id));
		assertUnique(data.households().stream().flatMap(h -> h.members().stream()).map(PersonRow::id));
		assertUnique(data.assistance().stream().map(DemoData.AssistanceRow::ref));
		assertUnique(data.services().stream().map(DemoData.ServiceRow::ref));
		assertUnique(data.services().stream().map(DemoData.ServiceRow::name));
	}

	@Test
	void everyHouseholdHasExactlyOnePrimaryContact() {
		assertThat(data.households()).allSatisfy(h -> assertThat(h.members())
				.filteredOn(p -> p.relationship() == Relationship.PRIMARY_CONTACT)
				.hasSize(1));
	}

	@Test
	void phonesComeOnlyFromRangesReservedForFiction() {
		Stream<String> phones = Stream.concat(
				data.households().stream().map(HouseholdRow::phone),
				data.services().stream().map(DemoData.ServiceRow::phone));
		assertThat(phones.filter(p -> p != null))
				.allMatch(p -> p.matches("02 (5550|7010) \\d{4}"));
	}

	@Test
	void assistanceIsInThePastYearWithNonNegativeAmountsForKnownHouseholds() {
		var householdIds = data.households().stream().map(HouseholdRow::id).toList();
		assertThat(data.assistance()).allSatisfy(a -> {
			assertThat(householdIds).contains(a.householdId());
			assertThat(a.assistedOn()).isBefore(TODAY).isAfterOrEqualTo(TODAY.minusDays(365));
			assertThat(a.amountAud()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
		});
	}

	@Test
	void assistanceNeverPredatesItsHousehold() {
		var registered = new java.util.HashMap<java.util.UUID, LocalDate>();
		data.households().forEach(h -> registered.put(h.id(),
				h.createdAt().atZone(DemoDataGenerator.SYDNEY).toLocalDate()));
		assertThat(data.assistance())
				.allSatisfy(a -> assertThat(a.assistedOn()).isAfter(registered.get(a.householdId())));
	}

	@Test
	void theNewHouseholdHasNoHistory() {
		var smyth = data.households().get(3);
		assertThat(smyth.ref()).isEqualTo("HH-0004");
		assertThat(data.assistance()).noneMatch(a -> a.householdId().equals(smyth.id()));
	}

	@Test
	void oneGuidelinePerAssistanceType() {
		assertThat(data.guidelines()).extracting(DemoData.GuidelineRow::assistanceType)
				.containsExactlyInAnyOrder(com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory.values());
	}

	@Test
	void seededAssistanceStaysWithinItsOwnGuidelineLimits() {
		var limits = new java.util.EnumMap<com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory, BigDecimal>(
				com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory.class);
		data.guidelines().forEach(g -> limits.put(g.assistanceType(), g.limitPerVisitAud()));
		assertThat(data.assistance())
				.allSatisfy(a -> assertThat(a.amountAud()).isLessThanOrEqualTo(limits.get(a.category())));
	}

	@Test
	void theFirstDemoHouseholdsTwoEnergyPaymentsAreARepeatUnderTheGuideline() {
		int energyWindow = data.guidelines().stream()
				.filter(g -> g.assistanceType() == com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory.ENERGY_BILL)
				.findFirst().orElseThrow().repeatWindowDays();
		var tran = data.households().get(0).id();
		assertThat(data.assistance())
				.filteredOn(a -> a.householdId().equals(tran)
						&& a.category() == com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory.ENERGY_BILL
						&& !a.assistedOn().isBefore(TODAY.minusDays(energyWindow)))
				.hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	void everyNeedCategoryHasServices() {
		assertThat(data.services()).extracting(DemoData.ServiceRow::needCategory)
				.containsOnly(com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory.values());
	}

	@Test
	void keepsTheHandMadeDemoHouseholds() {
		assertThat(data.households()).extracting(HouseholdRow::ref, HouseholdRow::familyName, HouseholdRow::suburb)
				.startsWith(
						org.assertj.core.groups.Tuple.tuple("HH-0001", "Tran", "Blacktown"),
						org.assertj.core.groups.Tuple.tuple("HH-0002", "Tran", "Mount Druitt"),
						org.assertj.core.groups.Tuple.tuple("HH-0003", "Smith", "Parramatta"),
						org.assertj.core.groups.Tuple.tuple("HH-0004", "Smyth", "Parramatta"));
	}

	private static <T> void assertUnique(Stream<T> values) {
		assertThat(values.toList()).doesNotHaveDuplicates();
	}
}

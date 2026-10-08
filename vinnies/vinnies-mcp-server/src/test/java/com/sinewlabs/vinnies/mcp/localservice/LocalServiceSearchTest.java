package com.sinewlabs.vinnies.mcp.localservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.sinewlabs.vinnies.mcp.localservice.LocalServiceSearch.ServiceMatch;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalServiceSearchTest {

	private final List<LocalService> services = List.of(
			service("SVC-001", "Blacktown Community Pantry", NeedCategory.FOOD, "Blacktown", "2148"),
			service("SVC-013", "Blacktown Food Relief Hub", NeedCategory.FOOD, "Blacktown", "2148"),
			service("SVC-003", "Parramatta Community Pantry", NeedCategory.FOOD, "Parramatta", "2150"),
			service("SVC-017", "Blacktown Energy Bill Help Desk", NeedCategory.ENERGY_BILL, "Blacktown", "2148"));

	@Test
	void returnsServicesForTheNeedInTheSuburbWithEverythingAReferralNeeds() {
		List<ServiceMatch> found = LocalServiceSearch.search(services, NeedCategory.FOOD, "Blacktown");

		assertThat(found).extracting(ServiceMatch::serviceRef).containsExactly("SVC-001", "SVC-013");
		assertThat(found).allSatisfy(s -> {
			assertThat(s.inSuburb()).isTrue();
			assertThat(s.needType()).isEqualTo(NeedCategory.FOOD);
			assertThat(s.address()).endsWith(", Blacktown NSW 2148");
			assertThat(s.hours()).isNotBlank();
			assertThat(s.eligibility()).isNotBlank();
		});
	}

	@Test
	void neverMixesInServicesForAnotherNeed() {
		assertThat(LocalServiceSearch.search(services, NeedCategory.ENERGY_BILL, "Blacktown"))
				.extracting(ServiceMatch::serviceRef).containsExactly("SVC-017");
	}

	@Test
	void toleratesASmallMisspellingOfTheSuburb() {
		assertThat(LocalServiceSearch.search(services, NeedCategory.FOOD, "Paramatta"))
				.extracting(ServiceMatch::serviceRef).containsExactly("SVC-003");
	}

	@Test
	void withNoneInTheSuburbOffersOthersFlaggedAsElsewhere() {
		List<ServiceMatch> found = LocalServiceSearch.search(services, NeedCategory.FOOD, "Bondi");

		assertThat(found).isNotEmpty().allSatisfy(s -> assertThat(s.inSuburb()).isFalse());
	}

	@Test
	void returnsAtMostFive() {
		List<LocalService> many = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			many.add(service("SVC-1%02d".formatted(i), "Pantry " + i, NeedCategory.FOOD, "Penrith", "2750"));
		}
		assertThat(LocalServiceSearch.search(many, NeedCategory.FOOD, "Penrith")).hasSize(5);
	}

	private static LocalService service(String ref, String name, NeedCategory need, String suburb, String postcode) {
		return new LocalService(UUID.randomUUID(), ref, name, need, suburb, postcode, "1 Station Street",
				"02 7010 0101", "Mon-Fri 9:00-15:00", "Anyone in need");
	}
}

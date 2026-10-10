package com.rootstock.core.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequiredFieldsTest {

	private final RequiredFields required = new RequiredFields();

	@Test
	void aCompleteRecordLacksNothing() {
		assertThat(required.missing(OntologyFixtures.sample(), "job-intake", complete())).isEmpty();
	}

	@Test
	void nullAbsentAndEmptyRequiredFieldsAreMissing() {
		Map<String, Object> record = complete();
		record.put("reportedOn", null);
		record.remove("priority");
		record.put("defects", List.of());

		assertThat(required.missing(OntologyFixtures.sample(), "job-intake", record))
				.extracting(RequiredFields.Missing::path)
				.containsExactly("priority", "reportedOn", "defects");
	}

	@Test
	void nestedFieldsAreCheckedWithTheirPath() {
		Map<String, Object> record = complete();
		record.put("tenant", map("phone", "0400 000 000"));
		record.put("defects", List.of(map("trade", "PLUMBING"), map("room", "kitchen")));

		assertThat(required.missing(OntologyFixtures.sample(), "job-intake", record))
				.containsExactly(new RequiredFields.Missing("tenant.name", null),
						new RequiredFields.Missing("defects[1].trade",
								"PLUMBING: Water, taps, toilets, drains and hot water; ELECTRICAL: Power points, lights, switchboard."));
	}

	@Test
	void idsInTheClientSystemAreNotExpected() {
		// propertyRef is required by the ontology, but extraction never fills ids.
		assertThat(complete()).doesNotContainKey("propertyRef");
		assertThat(required.missing(OntologyFixtures.sample(), "job-intake", complete())).isEmpty();
	}

	@Test
	void aMissingObjectIsReportedOnceWithItsEntityDescription() {
		Map<String, Object> record = complete();
		record.put("tenant", null);

		assertThat(required.missing(OntologyFixtures.sample(), "job-intake", record))
				.containsExactly(new RequiredFields.Missing("tenant", "The person who reported the problem."));
	}

	private static Map<String, Object> complete() {
		Map<String, Object> record = new HashMap<>();
		record.put("priority", "URGENT");
		record.put("reportedOn", "2026-10-01");
		record.put("tenant", map("name", "Sam"));
		record.put("defects", List.of(map("trade", "PLUMBING")));
		return record;
	}

	private static Map<String, Object> map(String key, Object value) {
		Map<String, Object> m = new HashMap<>();
		m.put(key, value);
		return m;
	}
}

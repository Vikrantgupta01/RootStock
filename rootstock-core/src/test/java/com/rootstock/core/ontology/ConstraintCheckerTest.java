package com.rootstock.core.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** hazards-are-urgent (sample pack): a job with any hazard is urgent. */
class ConstraintCheckerTest {

	private final ConstraintChecker checker = new ConstraintChecker();

	private List<ConstraintChecker.Violation> check(Object hazards, String priority) {
		Map<String, Object> record = new HashMap<>();
		record.put("hazards", hazards);
		record.put("priority", priority);
		return checker.check(OntologyFixtures.sample(), "job-intake", record);
	}

	@Test
	void aBrokenConstraintIsAViolationNamingTheFieldThatMustChange() {
		assertThat(check(List.of("FLOODING"), "LOW")).singleElement().satisfies(v -> {
			assertThat(v.constraint().id()).isEqualTo("hazards-are-urgent");
			assertThat(v.path()).isEmpty();
			assertThat(v.failed()).containsExactly("Job.priority");
		});
	}

	@Test
	void aConstraintThatHoldsOrDoesNotApplyIsFine() {
		assertThat(check(List.of("FLOODING"), "URGENT")).isEmpty();
		assertThat(check(List.of(), "LOW")).isEmpty();
		assertThat(check(null, "LOW")).isEmpty();
	}

	@Test
	void aMissingValueIsNotAViolationTheRequiredFieldCheckReportsIt() {
		assertThat(check(List.of("FLOODING"), null)).isEmpty();
	}
}

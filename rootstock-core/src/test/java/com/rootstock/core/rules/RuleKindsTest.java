package com.rootstock.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.Lookup;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each built-in rule kind, and the engine around them, on hand-made case states. */
class RuleKindsTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
	private static final Map<String, RuleKind> KINDS = RuleKinds.of(List.of());

	private static RuleContext context(Map<String, Object> record, Lookup... lookups) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put(CaseState.CASE_ID, "c");
		data.put("record", record);
		data.put("context", Map.of("lookups", List.of(lookups)));
		return new RuleContext(new CaseState(data), TODAY);
	}

	private static Lookup ok(String tool, Map<String, Object> arguments, Object result) {
		return new Lookup("plan", tool, arguments, "OK", result, null);
	}

	private static Rule rule(String kind, String severity, Map<String, Object> config) {
		return KINDS.get(kind).create(new RuleSpec("R-" + kind, kind, severity, null, null, null, config));
	}

	// ---- limit -----------------------------------------------------------------

	private static final Map<String, Object> LIMIT = Map.of("forEach", "$.record.assistance", "amount", "amountAud",
			"limit", Map.of("lookup", "get_guidelines", "match", Map.of("type", "$item.category"), "field", "limit",
					"default", Map.of("FOOD", 150, "RENT", 600)));

	@Test
	void limitAnAmountOverTheLookedUpLimitIsAnIssue() {
		Map<String, Object> record = Map.of("assistance", List.of(Map.of("category", "RENT", "amountAud", 500),
				Map.of("category", "RENT", "amountAud", 300.0)));

		List<CaseIssue> issues = rule("limit", "WARNING", LIMIT).evaluate(context(record,
				ok("get_guidelines", Map.of("type", "RENT"), Map.of("limit", "400.00"))));

		assertThat(issues).singleElement().isEqualTo(new CaseIssue("R-limit", CaseIssue.WARNING, CaseIssue.REVIEWER,
				"500 is over the limit of 400", "assistance[0].amountAud", CaseIssue.BUSINESS));
	}

	@Test
	void limitWithoutALookupTheDefaultForTheKeyIsUsed() {
		Map<String, Object> record = Map.of("assistance", List.of(Map.of("category", "FOOD", "amountAud", 200),
				Map.of("category", "ENERGY_BILL", "amountAud", 9999), Map.of("category", "FOOD")));

		// FOOD has a default (150); ENERGY_BILL has none, so it is not checked; no amount, nothing to check.
		assertThat(rule("limit", "WARNING", LIMIT).evaluate(context(record))).extracting(CaseIssue::path)
				.containsExactly("assistance[0].amountAud");
	}

	// ---- frequency ---------------------------------------------------------------

	private static final Map<String, Object> FREQUENCY = Map.of("forEach", "$.record.needs", "key", "category",
			"history", Map.of("lookup", "get_history", "list", "assistance", "key", "type", "date", "date"),
			"window", Map.of("lookup", "get_guidelines", "match", Map.of("type", "$item.category"), "field",
					"windowDays", "default", 90),
			"since", "$.record.visitDate");

	private static Lookup history(String... typeAndDate) {
		List<Map<String, Object>> entries = new java.util.ArrayList<>();
		for (int i = 0; i < typeAndDate.length; i += 2) {
			entries.add(Map.of("type", typeAndDate[i], "date", typeAndDate[i + 1], "amountAud", 100));
		}
		return ok("get_history", Map.of("householdRef", "HH-1"), Map.of("assistance", entries));
	}

	@Test
	void frequencyTheSameThingInsideTheWindowIsARepeatOncePerKey() {
		Map<String, Object> record = Map.of("visitDate", "2026-10-06", "needs", List.of(Map.of("category", "ENERGY_BILL"),
				Map.of("category", "ENERGY_BILL"), Map.of("category", "FOOD")));

		List<CaseIssue> issues = rule("frequency", "WARNING", FREQUENCY).evaluate(context(record,
				history("ENERGY_BILL", "2026-09-15", "ENERGY_BILL", "2026-07-23", "FOOD", "2026-05-01")));

		assertThat(issues).singleElement().satisfies(i -> {
			assertThat(i.message()).isEqualTo("ENERGY_BILL was already given on 2026-09-15, within the 90-day window");
			assertThat(i.path()).isEqualTo("needs[0].category");
		});
	}

	@Test
	void frequencyTheWindowComesFromTheLookupAndEndsAtTheVisit() {
		Map<String, Object> record = Map.of("visitDate", "2026-10-06", "needs", List.of(Map.of("category", "FOOD")));
		Lookup fourteenDays = ok("get_guidelines", Map.of("type", "FOOD"), Map.of("windowDays", 14));

		assertThat(rule("frequency", "WARNING", FREQUENCY).evaluate(context(record, fourteenDays,
				history("FOOD", "2026-09-20")))).isEmpty();
		assertThat(rule("frequency", "WARNING", FREQUENCY).evaluate(context(record, fourteenDays,
				history("FOOD", "2026-09-30")))).hasSize(1);
		// After the visit doesn't count.
		assertThat(rule("frequency", "WARNING", FREQUENCY).evaluate(context(record, fourteenDays,
				history("FOOD", "2026-10-08")))).isEmpty();
	}

	@Test
	void frequencyWithoutAHistoryLookupThereIsNothingToCheck() {
		Map<String, Object> record = Map.of("needs", List.of(Map.of("category", "FOOD")));

		assertThat(rule("frequency", "WARNING", FREQUENCY).evaluate(context(record))).isEmpty();
	}

	// ---- requires ------------------------------------------------------------------

	private static final Map<String, Object> REQUIRES = Map.of(
			"when", Map.of("succeeded", List.of("find"), "notSucceeded", List.of("get_history")),
			"require", ordered("$.record.household.contact", "present", "$.record.household.consentGiven", true));

	/** In the given order, as YAML keeps it. */
	private static Map<String, Object> ordered(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	@Test
	void requiresWhenTheSituationHoldsEachUnmetRequirementIsAnIssue() {
		Map<String, Object> record = Map.of("household", Map.of("consentGiven", false));

		List<CaseIssue> issues = rule("requires", "BLOCKING", REQUIRES).evaluate(context(record,
				ok("find", Map.of(), Map.of("matches", List.of()))));

		assertThat(issues).extracting(CaseIssue::path, CaseIssue::message, CaseIssue::answerableBy).containsExactly(
				org.assertj.core.groups.Tuple.tuple("household.contact", "household.contact is required here",
						CaseIssue.SUBMITTER),
				org.assertj.core.groups.Tuple.tuple("household.consentGiven", "household.consentGiven must be true",
						CaseIssue.SUBMITTER));
	}

	@Test
	void requiresOutsideTheSituationNothingIsRequired() {
		Map<String, Object> record = Map.of("household", Map.of());
		Rule rule = rule("requires", "BLOCKING", REQUIRES);

		// Not looked for at all (the client system was down): don't ask for consent on a guess.
		assertThat(rule.evaluate(context(record))).isEmpty();
		// Looked for and matched (history fetched): not a new household.
		assertThat(rule.evaluate(context(record, ok("find", Map.of(), Map.of()), history()))).isEmpty();
	}

	@Test
	void requiresEqualsAndPresentConditions() {
		Rule rule = rule("requires", "BLOCKING", Map.of("when", Map.of("equals", Map.of("$.record.urgency",
				List.of("HIGH", "MEDIUM")), "present", List.of("$.record.riskFlags")),
				"require", Map.of("$.record.followUp", "present")));

		assertThat(rule.evaluate(context(Map.of("urgency", "HIGH", "riskFlags", List.of("EVICTION"))))).hasSize(1);
		assertThat(rule.evaluate(context(Map.of("urgency", "LOW", "riskFlags", List.of("EVICTION"))))).isEmpty();
		assertThat(rule.evaluate(context(Map.of("urgency", "HIGH", "riskFlags", List.of())))).isEmpty();
	}

	// ---- the engine ------------------------------------------------------------------

	@Test
	void theEngineReportsEveryBrokenSpecAtOnce() {
		List<RuleSpec> specs = List.of(
				new RuleSpec("a", "limit", "WARNING", null, null, null, Map.of("forEach", "record.x", "amount", "y",
						"limit", 1)),
				new RuleSpec("b", "often", "WARNING", null, null, null, Map.of()),
				new RuleSpec("a", "requires", "WARNING", null, null, null, Map.of("require", Map.of("$.record.x",
						"present"))),
				new RuleSpec("c", "requires", "WARNING", null, null, null, Map.of("when", Map.of("sometimes", true),
						"require", Map.of("$.record.x", "present"))));

		assertThat(RuleEngine.problems(specs, KINDS)).containsExactly(
				"rule 'a': 'forEach' should be a state path such as $.record.needs, not 'record.x'",
				"rule 'b': unknown kind 'often'; known: [frequency, limit, requires]",
				"rule id 'a' is used twice",
				"rule 'c': 'when' may only have [succeeded, notSucceeded, present, equals]");
		assertThatThrownBy(() -> RuleEngine.build(specs, KINDS)).hasMessageContaining("unknown kind 'often'");
	}

	@Test
	void aRuleThatFailsWhileRunningIsAWarningNotAFailedCase() {
		Rule broken = new Rule() {

			@Override
			public String id() {
				return "R9";
			}

			@Override
			public List<CaseIssue> evaluate(RuleContext context) {
				throw new IllegalStateException("bad data");
			}
		};

		assertThat(new RuleEngine(List.of(broken)).evaluate(context(Map.of()))).singleElement().satisfies(i -> {
			assertThat(i.severity()).isEqualTo(CaseIssue.WARNING);
			assertThat(i.message()).isEqualTo("Rule R9 could not run: bad data");
		});
	}

	@Test
	void aMessageCanUseTheKindsPlaceholdersAndAnAnswerableByOverride() {
		Rule rule = KINDS.get("limit").create(new RuleSpec("R02", "limit", "BLOCKING", CaseIssue.EXTERNAL, null,
				"{item.category} ${amount} > ${limit}", LIMIT));

		assertThat(rule.evaluate(context(Map.of("assistance", List.of(Map.of("category", "FOOD", "amountAud", 151))))))
				.singleElement().satisfies(i -> {
					assertThat(i.message()).isEqualTo("FOOD $151 > $150");
					assertThat(i.answerableBy()).isEqualTo(CaseIssue.EXTERNAL);
				});
	}
}

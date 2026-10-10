package com.rootstock.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RouteConditionsTest {

	private static final Set<String> LISTS = Set.of("issues", "actions");
	private static final Set<String> OBJECTS = Set.of("review", "record");
	private static final Set<String> KNOWN = Set.of("issues", "actions", "review", "record");

	private static GraphDefinition.RouteSpec when(Map<String, Object> when, String to) {
		return new GraphDefinition.RouteSpec(when, to, null);
	}

	private static GraphDefinition.RouteSpec otherwise(String to) {
		return new GraphDefinition.RouteSpec(null, null, to);
	}

	private static CaseState state(Map<String, Object> values) {
		return new CaseState(new HashMap<>(values));
	}

	private final Function<CaseState, String> afterValidate = RouteConditions.compile(List.of(
			when(Map.of("issues.anySeverity", "BLOCKING", "issues.answerableBy", "SUBMITTER"), "clarify"),
			when(Map.of("issues.anySeverity", "BLOCKING"), "chase"),
			otherwise("draft")), LISTS, OBJECTS, KNOWN);

	@Test
	void noIssuesTakesTheDefault() {
		assertThat(afterValidate.apply(state(Map.of("issues", List.of())))).isEqualTo("draft");
		assertThat(afterValidate.apply(state(Map.of()))).isEqualTo("draft");
	}

	@Test
	void aBlockingIssueTheSubmitterCanAnswerGoesToClarify() {
		assertThat(afterValidate.apply(state(Map.of("issues", List.of(
				new CaseIssue("R1", CaseIssue.BLOCKING, CaseIssue.SUBMITTER, "date missing"))))))
				.isEqualTo("clarify");
	}

	@Test
	void bothConditionsMustHoldForTheSameIssue() {
		// A warning the submitter could answer, plus a blocking one only the household can: not clarify.
		assertThat(afterValidate.apply(state(Map.of("issues", List.of(
				new CaseIssue("R1", CaseIssue.WARNING, CaseIssue.SUBMITTER, "summary short"),
				new CaseIssue("R2", CaseIssue.BLOCKING, CaseIssue.EXTERNAL, "consent missing"))))))
				.isEqualTo("chase");
	}

	@Test
	void plainMapsWorkAsWellAsRecords() {
		assertThat(afterValidate.apply(state(Map.of("issues", List.of(
				Map.of("severity", "BLOCKING", "answerableBy", "SUBMITTER")))))).isEqualTo("clarify");
	}

	@Test
	void equalsAndExistsAndCount() {
		Function<CaseState, String> route = RouteConditions.compile(List.of(
				when(Map.of("review.decision", List.of("APPROVED", "EDITED"), "record.household.exists", true), "commit"),
				when(Map.of("actions.countAbove", 1), "split"),
				when(Map.of("actions.includesType", "CHASE_MESSAGE"), "await"),
				otherwise("END")), LISTS, OBJECTS, KNOWN);

		assertThat(route.apply(state(Map.of("review", Map.of("decision", "EDITED"), "record", Map.of("household", Map.of())))))
				.isEqualTo("commit");
		assertThat(route.apply(state(Map.of("review", Map.of("decision", "EDITED"), "record", Map.of()))))
				.isEqualTo("END");
		assertThat(route.apply(state(Map.of("actions", List.of(new ProposedAction("A", "a"), new ProposedAction("B", "b"))))))
				.isEqualTo("split");
		assertThat(route.apply(state(Map.of("actions", List.of(new ProposedAction(ProposedAction.CHASE_MESSAGE, "ask"))))))
				.isEqualTo("await");
	}

	@Test
	void malformedConditionsAreExplained() {
		assertThatThrownBy(() -> RouteConditions.parse("issues.countAbove", "lots", LISTS, OBJECTS, KNOWN))
				.hasMessage("'issues.countAbove': countAbove needs a whole number, found 'lots'");
		assertThatThrownBy(() -> RouteConditions.parse("issues.severity", "BLOCKING", LISTS, OBJECTS, KNOWN))
				.hasMessageContaining("'issues' is a list; use one of [answerableBy, anySeverity, includesType]");
		assertThatThrownBy(() -> RouteConditions.parse("review", "APPROVED", LISTS, OBJECTS, KNOWN))
				.hasMessageContaining("should be <channel>.<field or test>");
		assertThatThrownBy(() -> RouteConditions.parse("record.household.exists", "yes", LISTS, OBJECTS, KNOWN))
				.hasMessage("'record.household.exists': exists needs true or false, found 'yes'");
	}

	@Test
	void unseenByHoldsWhenAnIssueWasNotAmongThoseTheReviewerSaw() {
		Function<CaseState, String> route = RouteConditions.compile(List.of(
				new GraphDefinition.RouteSpec(Map.of("issues.unseenBy", "review"), "back", null),
				new GraphDefinition.RouteSpec(null, null, "commit")), Set.of("issues"),
				Set.of("review"), Set.of("issues", "review"));
		Object r02 = Map.of("ruleId", "R02", "path", "assistance[0].amountAud");
		Object r03 = Map.of("ruleId", "R03", "path", "needs[0].category");
		Map<String, Object> review = Map.of("seen", List.of("R02|assistance[0].amountAud"));

		assertThat(route.apply(new CaseState(Map.of("issues", List.of(r02), "review", review))))
				.isEqualTo("commit");
		assertThat(route.apply(new CaseState(Map.of("issues", List.of(r02, r03), "review", review))))
				.isEqualTo("back");
		assertThat(route.apply(new CaseState(Map.of("issues", List.of(), "review", review))))
				.isEqualTo("commit");
	}
}

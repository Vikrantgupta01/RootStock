package com.rootstock.core.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OntologyValidatorTest {

	private final OntologyValidator validator = new OntologyValidator();

	private List<String> problems(String yaml) {
		return validator.validate(new OntologyLoader().parse(yaml), OntologyFixtures.core()).stream()
				.map(OntologyProblem::toString).toList();
	}

	private List<String> problemsAfter(String from, String to) {
		String yaml = OntologyFixtures.sampleText();
		assertThat(yaml).as("fixture text to replace").contains(from);
		return problems(yaml.replace(from, to));
	}

	@Test
	void theSamplePackIsValid() {
		assertThat(problems(OntologyFixtures.sampleText())).isEmpty();
	}

	@Test
	void anUnknownCoreConceptListsTheRealOnes() {
		assertThat(problemsAfter("extends: core.Case", "extends: core.Ticket")).containsExactly(
				"entities.Job.extends: unknown concept 'core.Ticket'; core concepts are [core.Party, core.Document, "
						+ "core.Case, core.Request, core.Action, core.Issue, core.Approval]");
	}

	@Test
	void aRelationToAnUnknownEntity() {
		assertThat(problemsAfter("fixes: { to: Defect }", "fixes: { to: Fault }"))
				.anySatisfy(p -> assertThat(p).startsWith("entities.Visit.relations.fixes.to: unknown entity 'Fault'"));
	}

	@Test
	void anUnknownVocabulary() {
		assertThat(problemsAfter("trade: { vocab: Trade, required: true }\n      room", "trade: { vocab: Trades, required: true }\n      room"))
				.containsExactly("entities.Defect.attributes.trade.vocab: unknown vocabulary 'Trades'; known: [Trade, "
						+ "Priority, Hazard, core.IssueSeverity, core.ApprovalDecision]");
	}

	@Test
	void anUnknownType() {
		assertThat(problemsAfter("room: { type: string }", "room: { type: text }")).containsExactly(
				"entities.Defect.attributes.room.type: unknown type 'text'; expected one of [string, boolean, integer, decimal, date, datetime]");
	}

	@Test
	void aConstraintWithAValueNotInTheVocabulary() {
		assertThat(problemsAfter("require: { Job.priority: URGENT }", "require: { Job.priority: CRITICAL }"))
				.containsExactly("constraints.hazards-are-urgent.require: 'Job.priority': 'CRITICAL' is not a value of Priority [LOW, URGENT]");
	}

	@Test
	void aConstraintOnAFieldThatDoesNotExist() {
		assertThat(problemsAfter("when: { Job.hazards: notEmpty }", "when: { Job.risks: notEmpty }"))
				.containsExactly("constraints.hazards-are-urgent.when: 'Job.risks': Job has no field 'risks'");
	}

	@Test
	void aProjectionThatCannotReachWhatItEmbeds() {
		// Contractor is only reached through Visit; without Visit embedded, nothing leads to it.
		assertThat(problemsAfter("embed: [Tenant, Defect, Visit]", "embed: [Tenant, Defect]")).containsExactly(
				"projections.job-intake.reference: no relation from Job (or the entities it embeds) leads to Contractor");
	}

	@Test
	void aProjectionIncludingAnUnknownField() {
		assertThat(problemsAfter("include: [priority, defects.trade]", "include: [priority, defects.colour]"))
				.containsExactly("projections.dispatch-view.include: 'defects.colour': Defect has no field 'colour'");
	}

	@Test
	void aSynonymThatMeansTwoThings() {
		assertThat(problemsAfter("synonyms: [sparks, power out]", "synonyms: [sparks, leak]")).containsExactly(
				"vocabularies.Trade.ELECTRICAL.synonyms: 'leak' is also a synonym of PLUMBING; a word can mean only one value");
	}

	@Test
	void aFieldThatClashesWithAnInheritedOne() {
		assertThat(problemsAfter("room: { type: string }", "room: { type: string }\n    relations:\n      also: { to: Tenant, field: trade }"))
				.containsExactly("entities.Defect: field 'trade' is declared twice");
	}

	@Test
	void aPackMustExtendTheCore() {
		assertThat(problemsAfter("extends: rootstock-core", "extends: something-else"))
				.containsExactly("metadata.extends: must be rootstock-core, found 'something-else'");
	}

	@Test
	void minAndMaxMustMakeSense() {
		assertThat(problemsAfter("estimateAud: { type: decimal, min: 0, max: 10000 }", "estimateAud: { type: string, min: 10, max: 1 }"))
				.containsExactly("entities.Visit.attributes.estimateAud: 'min' and 'max' apply only to integer and decimal attributes",
						"entities.Visit.attributes.estimateAud: min 10 is greater than max 1");
	}
}

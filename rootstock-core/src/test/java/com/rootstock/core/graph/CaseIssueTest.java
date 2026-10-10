package com.rootstock.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CaseIssueTest {

	@Test
	void anIssueSaysHowSeriousItIsWhoCanResolveItAndWhichLayerFoundIt() {
		CaseIssue issue = new CaseIssue("R02", CaseIssue.WARNING, CaseIssue.REVIEWER, "Over the limit", "assistance[0]",
				CaseIssue.BUSINESS);

		assertThat(issue.blocking()).isFalse();
		assertThat(new CaseIssue("x", CaseIssue.BLOCKING, CaseIssue.SUBMITTER, "Missing").blocking()).isTrue();
		assertThat(new CaseIssue("x", CaseIssue.BLOCKING, CaseIssue.SUBMITTER, "Missing").layer()).isNull();
	}

	@Test
	void onlyKnownValuesAndAReadableMessage() {
		assertThatThrownBy(() -> new CaseIssue("x", "FATAL", CaseIssue.SUBMITTER, "m"))
				.hasMessageContaining("severity 'FATAL'");
		assertThatThrownBy(() -> new CaseIssue("x", CaseIssue.WARNING, "NOBODY", "m"))
				.hasMessageContaining("answerableBy 'NOBODY'");
		assertThatThrownBy(() -> new CaseIssue("x", CaseIssue.WARNING, CaseIssue.REVIEWER, "m", null, "VIBES"))
				.hasMessageContaining("layer 'VIBES'");
		assertThatThrownBy(() -> new CaseIssue("x", CaseIssue.WARNING, CaseIssue.REVIEWER, " "))
				.hasMessageContaining("message");
	}
}

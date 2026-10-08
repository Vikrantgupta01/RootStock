package com.sinewlabs.vinnies.mcp.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class JaroWinklerTest {

	@Test
	void matchesTheReferenceValues() {
		// The worked examples from Winkler's paper, used by every implementation.
		assertThat(JaroWinkler.similarity("martha", "marhta")).isCloseTo(0.961, within(0.001));
		assertThat(JaroWinkler.similarity("dwayne", "duane")).isCloseTo(0.840, within(0.001));
		assertThat(JaroWinkler.similarity("dixon", "dicksonx")).isCloseTo(0.813, within(0.001));
	}

	@Test
	void identicalIsOneAndNothingInCommonIsZero() {
		assertThat(JaroWinkler.similarity("tran", "tran")).isEqualTo(1.0);
		assertThat(JaroWinkler.similarity("abc", "xyz")).isEqualTo(0.0);
		assertThat(JaroWinkler.similarity("", "tran")).isEqualTo(0.0);
	}
}

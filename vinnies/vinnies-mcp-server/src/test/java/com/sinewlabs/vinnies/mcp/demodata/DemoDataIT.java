package com.sinewlabs.vinnies.mcp.demodata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader.Outcome;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader.Summary;
import com.sinewlabs.vinnies.mcp.support.ThrowawaySchemaConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The seed and reset commands against RDS, in a throwaway schema: the same code
 * ./demo-data.sh runs. Same context configuration as the tool tests, so the run
 * shares one schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ThrowawaySchemaConfig.class)
class DemoDataIT {

	@Autowired
	DemoDataLoader demoData;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void runsInAThrowawaySchemaNotTheDemoOne() {
		assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).startsWith("vinnies_test_");
	}

	@Test
	void resetTwiceGivesTheSameData() {
		Summary first = demoData.reset();
		Summary second = demoData.reset();

		assertThat(second).isEqualTo(first);
		assertThat(first.households()).isEqualTo(50);
		assertThat(first.assistance()).isEqualTo(200);
		assertThat(first.services()).isEqualTo(40);
	}

	@Test
	void seedIntoEmptyTablesLoadsTheDemoDataAndSeedingAgainChangesNothing() {
		jdbc.execute("TRUNCATE assistance, person, household, local_service");

		Summary seeded = demoData.seed();
		Summary again = demoData.seed();

		assertThat(seeded.outcome()).isEqualTo(Outcome.SEEDED);
		assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_SEEDED);
		assertThat(again.fingerprint()).isEqualTo(seeded.fingerprint());
		assertThat(seeded.fingerprint()).isEqualTo(demoData.reset().fingerprint());
	}

	@Test
	void seedRefusesTablesHoldingOtherDataAndLeavesThemAlone() {
		demoData.reset();
		jdbc.update("INSERT INTO household (id, ref, family_name, suburb, postcode) VALUES (?, 'HH-9999', "
				+ "'Extra', 'Penrith', '2750')", UUID.randomUUID());
		String before = demoData.fingerprint();

		assertThatThrownBy(demoData::seed).isInstanceOf(IllegalStateException.class).hasMessageContaining("reset");
		assertThat(demoData.fingerprint()).isEqualTo(before);

		demoData.reset();   // back to the demo set for whatever runs next
	}
}

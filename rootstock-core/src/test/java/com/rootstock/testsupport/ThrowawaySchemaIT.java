package com.rootstock.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.customer.Customer;
import com.rootstock.core.customer.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The whole application boots against a throwaway schema in RDS: Flyway applies
 * every real migration there, Hibernate validates the entities against it, and a
 * repository reads and writes in it, never in Rootstock's own tables.
 */
@SpringBootTest
@Import(ThrowawaySchemaConfig.class)
class ThrowawaySchemaIT {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	CustomerRepository customers;

	@Autowired
	ThrowawaySchema schema;

	@Test
	void theAppRunsInItsOwnThrowawaySchema() {
		assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
				.isEqualTo(schema.name())
				.startsWith(ThrowawaySchema.PREFIX);
	}

	@Test
	void flywayAppliedEveryMigrationThere() {
		Integer applied = jdbc.queryForObject(
				"SELECT count(*) FROM " + schema.name() + ".flyway_schema_history WHERE success", Integer.class);
		assertThat(applied).isGreaterThanOrEqualTo(9);
	}

	@Test
	void aRepositoryWritesAndReadsInTheThrowawaySchema() {
		customers.save(new Customer("Test Customer", "throwaway@example.test", null, null, null, null));

		assertThat(customers.existsByEmailIgnoreCase("throwaway@example.test")).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema.name() + ".customer WHERE email = ?",
				Integer.class, "throwaway@example.test")).isEqualTo(1);
	}
}

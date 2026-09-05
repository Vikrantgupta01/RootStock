package com.rootstock.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.TestcontainersConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;

/**
 * Exercises the repository (and Flyway's V1/V2 migrations) against a real
 * Postgres. Skips automatically when Docker is not available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerRepositoryTest {

	@Autowired
	CustomerRepository repository;

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(
				DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping repository integration test");
	}

	@Test
	void savesAndFindsByEmailCaseInsensitively() {
		repository.save(new Customer("Ada Lovelace", "Ada@Example.com", null, null, null, null));

		assertThat(repository.existsByEmailIgnoreCase("ada@example.com")).isTrue();
		assertThat(repository.findByEmailIgnoreCase("ADA@EXAMPLE.COM")).isPresent();
	}

	@Test
	void defaultsStatusToProspect() {
		Customer saved = repository.save(new Customer("Grace Hopper", "grace@example.com", null, null, null, null));

		assertThat(saved.getStatus()).isEqualTo(CustomerStatus.PROSPECT);
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
	}
}

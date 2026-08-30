package com.rootstock;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;

/**
 * Boots the full application context against a Testcontainers Postgres (pgvector
 * image), runs the Flyway migrations, and verifies everything wires. AI models
 * are disabled by the {@code test} profile so no AWS credentials are needed.
 *
 * <p>Skips automatically when no Docker environment is available (e.g. a laptop
 * with Docker Desktop stopped); CI with Docker runs it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RootStockApplicationTests {

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(
				DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping full context integration test");
	}

	@Test
	void contextLoads() {
	}
}

package com.rootstock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spins up Postgres for integration tests and wires it to the application
 * datasource via {@link ServiceConnection}. Only document/version/job/profile
 * bookkeeping lives here now -- vector data is Bedrock's Aurora, not this --
 * but the pgvector image is still required: V1__init.sql unconditionally runs
 * {@code CREATE EXTENSION IF NOT EXISTS vector}, and plain postgres:16 doesn't
 * ship that extension's files at all (V1 can't be edited -- its checksum is
 * already locked in against every already-migrated database).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg16")
						.asCompatibleSubstituteFor("postgres"));
	}
}

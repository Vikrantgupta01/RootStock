package com.rootstock.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Import into an integration test to run Rootstock against its own throwaway
 * schema: created before the data source starts, migrated by Flyway, dropped
 * when the test context closes (for cached contexts, at the end of the run).
 *
 * <p>Two search paths on purpose. Flyway sees {@code <schema>, public}: an early
 * migration creates a {@code vector} column, and that type lives with the
 * extension in {@code public}. The application's connections see only the test
 * schema, so a query can never fall through to Rootstock's real tables, which
 * also live in {@code public}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ThrowawaySchemaConfig {

	@Bean
	ThrowawaySchema throwawaySchema(Environment env) {
		return ThrowawaySchema.create(env);
	}

	@Bean
	DynamicPropertyRegistrar throwawaySchemaProperties(ThrowawaySchema schema) {
		return registry -> {
			registry.add("spring.datasource.hikari.schema", schema::name);
			registry.add("spring.jpa.properties.hibernate.default_schema", schema::name);
			registry.add("spring.flyway.schemas", schema::name);
			registry.add("spring.flyway.default-schema", schema::name);
			// Indexed: as a plain value, Spring would split this list property at the comma.
			registry.add("spring.flyway.init-sqls[0]", () -> "SET search_path TO " + schema.name() + ", public");
			// A connection dropped mid-query (the laptop slept, the network changed)
			// fails the run after 5 minutes instead of hanging it forever.
			registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "300");
			// Tests don't trace, and don't run the background ingestion poller.
			registry.add("rootstock.observability.langfuse.enabled", () -> "false");
			registry.add("rootstock.rag.ingest.poller-enabled", () -> "false");
		};
	}
}

package com.sinewlabs.vinnies.mcp.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Import into an integration test to run the app against its own throwaway
 * schema. The schema is created before the data source starts and dropped when
 * the test context closes (Spring calls {@link ThrowawaySchema#close()}), which
 * for cached contexts is at the end of the test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ThrowawaySchemaConfig {

	@Bean
	ThrowawaySchema throwawaySchema(Environment env) {
		return ThrowawaySchema.create(env);
	}

	/** Points every connection, and Hibernate, at the throwaway schema instead of vinnies_mock. */
	@Bean
	DynamicPropertyRegistrar throwawaySchemaProperties(ThrowawaySchema schema) {
		return registry -> {
			registry.add("spring.datasource.hikari.schema", schema::name);
			registry.add("spring.jpa.properties.hibernate.default_schema", schema::name);
		};
	}
}

package com.rootstock.autoconfig.observability;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Derives OpenTelemetry's configuration from the Langfuse settings, so there is
 * one switch to think about instead of three that have to agree.
 *
 * <p>This exists because Boot only creates an OTLP span exporter when
 * {@code management.opentelemetry.tracing.export.otlp.endpoint} is set --
 * {@code OtlpTracingConfigurations.ConnectionDetails} is
 * {@code @ConditionalOnProperty} on exactly that key. Without it there is no
 * exporter, so an {@code OtlpHttpSpanExporterBuilderCustomizer} never runs and
 * nothing is ever sent, silently. The endpoint cannot simply be hard-coded in
 * {@code application.yml} either: that would create an exporter in environments
 * with no Langfuse keys, which would then POST unauthenticated and log a 401 for
 * every batch -- the same class of noise the OTLP metrics registry produced.
 *
 * <p>So the rule is applied here, where both facts are known at once:
 * <ul>
 *   <li>Langfuse enabled <em>and</em> fully configured: point the exporter at
 *       this host's OTLP endpoint.</li>
 *   <li>Anything else: turn OpenTelemetry off entirely, which makes traces,
 *       metrics and logging no-op. No exporter, no queue, no background thread,
 *       no log lines.</li>
 * </ul>
 *
 * <p>Runs after {@link ConfigDataEnvironmentPostProcessor} so {@code application.yml}
 * and any {@code .env}-derived variables have already been bound.
 */
public class LangfuseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	private static final String PROPERTY_SOURCE_NAME = "langfuse-derived";
	private static final String PREFIX = "rootstock.observability.langfuse.";
	private static final String OTEL_ENABLED = "management.opentelemetry.enabled";
	private static final String OTLP_ENDPOINT = "management.opentelemetry.tracing.export.otlp.endpoint";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Map<String, Object> derived = new HashMap<>();
		if (usable(environment)) {
			derived.put(OTEL_ENABLED, "true");
			derived.put(OTLP_ENDPOINT, tracesEndpoint(environment));
		}
		else {
			derived.put(OTEL_ENABLED, "false");
		}
		// addFirst: this is a derivation, not a default, and must beat whatever
		// application.yml said about the two keys above.
		environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, derived));
	}

	private static boolean usable(ConfigurableEnvironment environment) {
		return environment.getProperty(PREFIX + "enabled", Boolean.class, false)
				&& StringUtils.hasText(environment.getProperty(PREFIX + "host"))
				&& StringUtils.hasText(environment.getProperty(PREFIX + "public-key"))
				&& StringUtils.hasText(environment.getProperty(PREFIX + "secret-key"));
	}

	private static String tracesEndpoint(ConfigurableEnvironment environment) {
		String host = environment.getProperty(PREFIX + "host", "").trim();
		return StringUtils.trimTrailingCharacter(host, '/') + "/api/public/otel/v1/traces";
	}

	@Override
	public int getOrder() {
		return ConfigDataEnvironmentPostProcessor.ORDER + 10;
	}
}

package com.rootstock.autoconfig.observability;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpHttpSpanExporterBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Points the OTLP span exporter at Langfuse and authenticates it.
 *
 * <p>Done as a builder customizer rather than literal
 * {@code management.opentelemetry.tracing.export.otlp.headers.Authorization}
 * configuration so the key pair stays as two plain environment variables --
 * Langfuse wants {@code Basic base64(public:secret)}, and nobody should have to
 * produce that by hand or keep a pre-encoded blob in sync with the keys it came
 * from.
 *
 * <p>Nothing here is created unless {@code rootstock.observability.langfuse.enabled}
 * is true, and even then a missing key is reported once and the exporter is left
 * unconfigured. The stronger guarantee lives in {@code application.yml}:
 * {@code management.opentelemetry.enabled} is bound to the same flag, so when
 * Langfuse is switched off OpenTelemetry is no-op throughout and no exporter,
 * queue or background thread exists to misbehave.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LangfuseProperties.class)
@ConditionalOnProperty(prefix = "rootstock.observability.langfuse", name = "enabled")
public class LangfuseExporterConfig {

	private static final Logger log = LoggerFactory.getLogger(LangfuseExporterConfig.class);

	/** Langfuse's ingestion contract version; it rejects traces sent without it. */
	private static final String INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version";
	private static final String INGESTION_VERSION = "4";

	@Bean
	OtlpHttpSpanExporterBuilderCustomizer langfuseSpanExporterCustomizer(LangfuseProperties properties) {
		if (!properties.isUsable()) {
			// One line, at startup, naming exactly what is absent. The alternative
			// -- letting the exporter run unconfigured -- is a 401 on every export
			// batch for the lifetime of the process.
			log.warn("Langfuse tracing is enabled but not configured; missing {}. No traces will be exported.",
					properties.missingFields());
			return builder -> {
			};
		}
		String credentials = Base64.getEncoder().encodeToString(
				(properties.publicKey().trim() + ":" + properties.secretKey().trim()).getBytes(StandardCharsets.UTF_8));
		String endpoint = properties.tracesEndpoint();
		log.info("Exporting traces to Langfuse at {}", endpoint);
		return (OtlpHttpSpanExporterBuilder builder) -> builder
				.setEndpoint(endpoint)
				.addHeader("Authorization", "Basic " + credentials)
				.addHeader(INGESTION_VERSION_HEADER, INGESTION_VERSION);
	}
}

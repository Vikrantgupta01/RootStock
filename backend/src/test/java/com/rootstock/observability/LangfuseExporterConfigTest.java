package com.rootstock.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.TestcontainersConfiguration;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;

/**
 * The contract that matters in every environment that is <em>not</em> wired to
 * Langfuse: the application starts, and nothing tries to export anywhere.
 *
 * <p>Worth testing rather than assuming. The same assumption about a sibling
 * mechanism -- the OTLP <em>metrics</em> registry, which lives under a different
 * property namespace -- turned out to be wrong and logged a ConnectException on
 * every publish interval in every environment.
 */
class LangfuseExporterConfigTest {

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping Langfuse exporter wiring test");
	}

	@Nested
	@SpringBootTest
	@ActiveProfiles("test")
	@Import(TestcontainersConfiguration.class)
	class WhenTracingIsDisabled {

		@Autowired
		ApplicationContext context;

		@Test
		void nothingLangfuseIsWiredAtAll() {
			// application-test.yml leaves the flag off, which is the default state
			// of any environment that has not opted in.
			assertThat(context.getBeanNamesForType(SpanExporter.class)).isEmpty();
			assertThat(context.getBeanNamesForType(LangfuseExporterConfig.class)).isEmpty();
			assertThat(context.getBeanNamesForType(ChatContentObservationFilter.class)).isEmpty();
		}
	}

	@Nested
	@SpringBootTest(properties = {
			"rootstock.observability.langfuse.enabled=true",
			"rootstock.observability.langfuse.host=https://us.cloud.langfuse.com",
			// The misconfiguration that matters: the flag is on, the keys are not set.
			"rootstock.observability.langfuse.public-key=",
			"rootstock.observability.langfuse.secret-key="
	})
	@ActiveProfiles("test")
	@Import(TestcontainersConfiguration.class)
	class WhenEnabledWithoutKeys {

		@Autowired
		ApplicationContext context;

		@Test
		void theApplicationStartsAndNoExporterIsCreated() {
			// Reaching this assertion at all is half the test: the context loaded.
			// The other half is that no exporter exists to retry against a default
			// endpoint forever. LangfuseExporterConfig logs one WARN naming the
			// missing keys instead.
			assertThat(context.getBeanNamesForType(SpanExporter.class)).isEmpty();
		}
	}
}

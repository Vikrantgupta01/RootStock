package com.rootstock.rag;

import com.rootstock.rag.tenant.TenantFilter;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the {@code rootstock.rag.*} properties and the bounded executor the
 * ingestion poller hands work to.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

	public static final String INGESTION_EXECUTOR = "ragIngestionExecutor";

	@Bean
	org.springframework.boot.web.servlet.FilterRegistrationBean<TenantFilter> tenantFilterRegistration(
			RagProperties properties) {
		var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
				new TenantFilter(properties));
		registration.addUrlPatterns("/api/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}

	@Bean(INGESTION_EXECUTOR)
	Executor ragIngestionExecutor(RagProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("rag-ingest-");
		executor.setCorePoolSize(Math.max(1, properties.ingest().batchSize()));
		executor.setMaxPoolSize(Math.max(2, properties.ingest().batchSize() * 2));
		executor.setQueueCapacity(0);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.initialize();
		return executor;
	}
}

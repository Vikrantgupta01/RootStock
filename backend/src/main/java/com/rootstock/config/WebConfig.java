package com.rootstock.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC-level configuration: CORS for the local React dev server (and any origins
 * listed under {@code rootstock.cors.allowed-origins}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RootStockProperties.class)
public class WebConfig implements WebMvcConfigurer {

	private final RootStockProperties properties;

	public WebConfig(RootStockProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*");
	}
}

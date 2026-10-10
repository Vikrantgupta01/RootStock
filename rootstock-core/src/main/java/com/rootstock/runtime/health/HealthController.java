package com.rootstock.runtime.health;

import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight liveness endpoint for the frontend to poll. Spring Boot Actuator's
 * {@code /actuator/health} remains the source of truth for orchestration.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

	private final ObjectProvider<BuildProperties> buildProperties;

	public HealthController(ObjectProvider<BuildProperties> buildProperties) {
		this.buildProperties = buildProperties;
	}

	@GetMapping
	public HealthResponse health() {
		String version = buildProperties.stream()
				.map(BuildProperties::getVersion)
				.findFirst()
				.orElse("dev");
		return new HealthResponse("UP", "RootStock", version, Instant.now());
	}

	public record HealthResponse(String status, String app, String version, Instant timestamp) {
	}
}

package com.rootstock.observability;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Links to traces in the Langfuse UI. A trace page lives under its project, so
 * the project id is looked up once (Langfuse's public API, with the same keys
 * the exporter uses) and kept. Without usable Langfuse settings there are no
 * links: null, never a guess. The settings bean exists only while Langfuse is
 * enabled, hence the provider.
 */
@Component
public class LangfuseLinks {

	private static final Logger log = LoggerFactory.getLogger(LangfuseLinks.class);

	private final ObjectProvider<LangfuseProperties> settings;
	private volatile String projectId;

	public LangfuseLinks(ObjectProvider<LangfuseProperties> settings) {
		this.settings = settings;
	}

	public String traceUrl(String traceId) {
		LangfuseProperties properties = settings.getIfAvailable();
		if (traceId == null || properties == null || !properties.isUsable()) {
			return null;
		}
		String project = projectId(properties);
		return project == null ? null : host(properties) + "/project/" + project + "/traces/" + traceId;
	}

	private String projectId(LangfuseProperties properties) {
		String id = projectId;
		if (id != null) {
			return id;
		}
		try {
			String auth = Base64.getEncoder().encodeToString(
					(properties.publicKey() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8));
			Map<?, ?> body = RestClient.create().get().uri(host(properties) + "/api/public/projects")
					.header("Authorization", "Basic " + auth)
					.retrieve()
					.body(Map.class);
			Object data = body == null ? null : body.get("data");
			if (data instanceof List<?> projects && !projects.isEmpty() && projects.get(0) instanceof Map<?, ?> p) {
				projectId = String.valueOf(p.get("id"));
			}
		}
		catch (RuntimeException ex) {
			log.warn("Could not look up the Langfuse project for trace links: {}", ex.getMessage());
		}
		return projectId;
	}

	private static String host(LangfuseProperties properties) {
		String host = properties.host().trim();
		return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
	}
}

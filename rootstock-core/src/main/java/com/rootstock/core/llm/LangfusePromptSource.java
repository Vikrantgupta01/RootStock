package com.rootstock.core.llm;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads prompts from Langfuse's public API
 * ({@code GET /api/public/v2/prompts/{name}?label=}), authenticated with the
 * project's key pair. Read only: prompts are written in Langfuse itself.
 */
public final class LangfusePromptSource implements PromptSource {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient http;
	private final String host;
	private final String authorization;
	private final Duration timeout;

	public LangfusePromptSource(String host, String publicKey, String secretKey, Duration timeout) {
		this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
		this.host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
		this.authorization = "Basic " + Base64.getEncoder()
				.encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
		this.timeout = timeout;
	}

	@Override
	public Optional<PromptTemplate> fetch(String name, String label) {
		// A folder-style name (acme/extract-case) is one path segment: its slash is encoded.
		URI uri = URI.create(host + "/api/public/v2/prompts/" + URLEncoder.encode(name, StandardCharsets.UTF_8)
				+ "?label=" + URLEncoder.encode(label, StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization", authorization)
				.header("Accept", "application/json").GET().build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new IllegalStateException("Langfuse could not be reached: " + e.getMessage(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while fetching a prompt from Langfuse", e);
		}
		if (response.statusCode() == 404) {
			return Optional.empty();
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Langfuse answered " + response.statusCode() + " for prompt '" + name + "'");
		}
		return Optional.of(parse(response.body(), label));
	}

	static PromptTemplate parse(String body, String label) {
		JsonNode node = JSON.readTree(body);
		JsonNode prompt = node.path("prompt");
		List<PromptTemplate.Part> parts = new ArrayList<>();
		if (prompt.isArray()) {
			for (JsonNode m : prompt) {
				// Chat prompts may hold placeholders for message lists; Rootstock fills only text.
				if (m.hasNonNull("role") && m.hasNonNull("content")) {
					parts.add(new PromptTemplate.Part(m.get("role").asString(), m.get("content").asString()));
				}
			}
		}
		else {
			parts.add(new PromptTemplate.Part("user", prompt.asString()));
		}
		return new PromptTemplate(node.path("name").asString(), label,
				node.hasNonNull("version") ? node.get("version").asInt() : null, PromptTemplate.Source.LANGFUSE, parts);
	}
}

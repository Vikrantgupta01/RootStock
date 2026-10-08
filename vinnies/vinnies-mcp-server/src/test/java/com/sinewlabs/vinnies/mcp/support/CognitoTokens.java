package com.sinewlabs.vinnies.mcp.support;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real access tokens from Cognito (client credentials, Iteration 2.4) for the
 * integration tests, one per scope, cached for the run: each request is billed.
 * Settings: VINNIES_MCP_TOKEN_URL, VINNIES_MCP_CLIENT_ID, VINNIES_MCP_CLIENT_SECRET.
 */
public final class CognitoTokens {

	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

	private CognitoTokens() {
	}

	public static String read() {
		return token("vinnies/read");
	}

	public static String write() {
		return token("vinnies/write");
	}

	public static String token(String scope) {
		return CACHE.computeIfAbsent(scope, CognitoTokens::fetch);
	}

	private static String fetch(String scope) {
		String url = env("VINNIES_MCP_TOKEN_URL");
		String basic = Base64.getEncoder().encodeToString(
				(env("VINNIES_MCP_CLIENT_ID") + ":" + env("VINNIES_MCP_CLIENT_SECRET")).getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Basic " + basic)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope="
						+ URLEncoder.encode(scope, StandardCharsets.UTF_8)))
				.build();
		try {
			HttpResponse<String> response = HttpClient.newHttpClient().send(request,
					HttpResponse.BodyHandlers.ofString());
			Matcher token = ACCESS_TOKEN.matcher(response.body());
			if (response.statusCode() != 200 || !token.find()) {
				throw new IllegalStateException("Cognito refused a " + scope + " token: HTTP " + response.statusCode());
			}
			return token.group(1);
		}
		catch (java.io.IOException | InterruptedException ex) {
			throw new IllegalStateException("Could not reach the Cognito token endpoint", ex);
		}
	}

	private static String env(String key) {
		String value = System.getenv(key);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(key + " is not set: load the settings first "
					+ "(set -a && source .env && set +a).");
		}
		return value;
	}
}

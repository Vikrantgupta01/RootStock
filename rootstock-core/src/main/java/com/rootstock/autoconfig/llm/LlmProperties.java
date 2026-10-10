package com.rootstock.autoconfig.llm;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code rootstock.llm.*}.
 *
 * @param profiles model profiles by name ({@code extraction}, {@code fast}, {@code drafting}…), which agents name
 *                 in their {@code model:}
 * @param prompts  how prompts are fetched from Langfuse
 */
@ConfigurationProperties(prefix = "rootstock.llm")
public record LlmProperties(Map<String, Profile> profiles, @DefaultValue Prompts prompts) {

	public LlmProperties {
		profiles = profiles == null ? Map.of() : profiles;
	}

	/**
	 * @param model a Bedrock model or inference profile id; blank for the default chat model
	 */
	public record Profile(String model, Double temperature, Integer maxTokens) {
	}

	/**
	 * @param cacheTtl     how long a fetched prompt is used before Langfuse is asked again
	 * @param fetchTimeout how long to wait for Langfuse before using the bundled copy
	 */
	public record Prompts(@DefaultValue("5m") Duration cacheTtl, @DefaultValue("5s") Duration fetchTimeout) {
	}
}

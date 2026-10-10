package com.rootstock.core.llm;

/**
 * A named model setting an agent asks for ({@code model: extraction} in its
 * YAML), so packs choose a kind of model, not a Bedrock model id.
 *
 * @param model       the Bedrock model or inference profile id; null for the configured default
 * @param temperature null for the model's default
 * @param maxTokens   null for the model's default
 */
public record ModelProfile(String model, Double temperature, Integer maxTokens) {
}

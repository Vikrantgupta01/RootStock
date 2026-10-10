package com.rootstock.core.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One agent as written in {@code agents/<name>.yaml}: which building block it
 * uses ({@code type}), with what model, prompt, input and output. A graph node
 * refers to it by name.
 */
public record AgentDefinition(Metadata metadata, Spec spec) {

	public String name() {
		return metadata.name();
	}

	public record Metadata(String name, String version, String description) {
	}

	/**
	 * @param model  a model profile, mapped to a Bedrock model in configuration
	 * @param input  state paths the agent reads, e.g. {@code notes: $.rawInput}
	 */
	public record Spec(String type, String model, Prompt prompt, Map<String, String> input, Output output, Tools tools,
			Limits limits) {

		public Spec {
			input = input == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input));
		}
	}

	/** A prompt kept in Langfuse, by name and label. */
	public record Prompt(String name, String label) {
	}

	/**
	 * @param projection the ontology projection whose schema the output must match, if any
	 * @param writeTo    the state channel the result goes into
	 */
	public record Output(String projection, String writeTo) {
	}

	/** MCP tools the agent may call: they must also be on its node's allowlist in tools.yaml. */
	public record Tools(String connection, List<String> allow) {

		public Tools {
			allow = allow == null ? List.of() : List.copyOf(allow);
		}
	}

	public record Limits(Integer timeoutSeconds, Integer retries, Integer maxIterations) {
	}
}

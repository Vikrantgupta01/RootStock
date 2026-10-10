package com.rootstock.core.tools;

/**
 * A tool as its client system describes it, for a model to choose and call it.
 *
 * @param description what the tool does, in the client system's words
 * @param inputSchema the JSON Schema of its arguments, as JSON text
 */
public record ToolSpec(String name, String description, String inputSchema) {
}

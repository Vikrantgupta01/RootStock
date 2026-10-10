package com.rootstock.core.llm;

import java.util.Optional;

/** Somewhere prompts are kept and versioned: Langfuse. */
public interface PromptSource {

	/**
	 * @return empty when there is no such prompt or label
	 * @throws RuntimeException when the source cannot be reached
	 */
	Optional<PromptTemplate> fetch(String name, String label);
}

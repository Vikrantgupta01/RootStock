package com.rootstock.core.llm;

/** Neither Langfuse nor the pack has the prompt an agent asks for. */
public class PromptNotFoundException extends RuntimeException {

	public PromptNotFoundException(String message) {
		super(message);
	}
}

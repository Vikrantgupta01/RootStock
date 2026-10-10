package com.rootstock.core.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code rootstock.agent.*}.
 *
 * @param maxIterations how many Reason steps one run may take. Each is a model
 *                      call, so this is also the cost ceiling of a single
 *                      question; the last one is told to answer with what it has.
 */
@ConfigurationProperties(prefix = "rootstock.agent")
public record AgentProperties(
		@DefaultValue("6") int maxIterations,
		@DefaultValue(DEFAULT_SYSTEM_PROMPT) String systemPrompt) {

	public AgentProperties {
		if (maxIterations < 1) {
			throw new IllegalArgumentException("rootstock.agent.max-iterations must be at least 1");
		}
	}

	static final String DEFAULT_SYSTEM_PROMPT = """
			You are RootStock's agent. Work step by step: before each tool call, say in \
			one short sentence what you need and why. Use searchKnowledgeBase for anything \
			about the organisation's own documents, policies or data -- never guess those. \
			If a search comes back empty or off-topic, try a differently worded query \
			before giving up. Cite passages you rely on as [n] with their source. If the \
			knowledge base does not contain the answer, say so plainly.""";
}

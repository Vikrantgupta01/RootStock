package com.rootstock.core.agent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * The agent's tools as Spring AI callbacks, and the single place they are run.
 *
 * <p>Every tool call goes through {@link #execute}, which gives tracing one
 * method to advise for all tools (see {@code TracingAspect}) and keeps
 * {@link AgentTools} itself free of a proxy -- {@link ToolCallbacks#from} reads
 * {@code @Tool} annotations off the class, which a CGLIB subclass would hide.
 */
@Component
public class AgentToolbox {

	private static final Logger log = LoggerFactory.getLogger(AgentToolbox.class);

	private final Map<String, ToolCallback> callbacks;

	public AgentToolbox(AgentTools tools) {
		Map<String, ToolCallback> byName = new LinkedHashMap<>();
		Arrays.stream(ToolCallbacks.from(tools)).forEach(c -> byName.put(c.getToolDefinition().name(), c));
		this.callbacks = Map.copyOf(byName);
	}

	/** The definitions offered to the model on every Reason step. */
	public List<ToolCallback> callbacks() {
		return List.copyOf(callbacks.values());
	}

	/**
	 * Runs one tool call. Never throws: a failure becomes the observation, so the
	 * model sees what went wrong and can try something else instead of the whole
	 * run failing on, say, one malformed argument.
	 */
	public String execute(String toolName, String arguments, ToolContext context) {
		ToolCallback callback = callbacks.get(toolName);
		if (callback == null) {
			return "Error: there is no tool named '" + toolName + "'. Available: " + callbacks.keySet() + ".";
		}
		try {
			return callback.call(arguments, context);
		}
		catch (RuntimeException ex) {
			log.warn("Agent tool {} failed for arguments {}", toolName, arguments, ex);
			return "Error: " + toolName + " failed (" + ex.getMessage() + ").";
		}
	}
}

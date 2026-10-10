package com.rootstock.core.agent;

import com.rootstock.core.agent.dto.AgentStep;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/**
 * The state one ReAct run carries through the graph. Nodes return partial
 * updates; the channels decide how each update merges in.
 *
 * <ul>
 * <li>{@code messages} -- the working transcript: prior turns, the question,
 * then each assistant reply and tool result as they happen. Appended to, never
 * replaced. Duplicates are allowed on purpose: a person who answers "yes" twice
 * said it twice, and the plain appender would silently drop the second.</li>
 * <li>{@code steps} -- the Act/Observe record returned to the caller.</li>
 * <li>{@code iterations} -- Reason steps taken so far; overwritten.</li>
 * <li>{@code toolContext} -- tenant and groups for the tools; never sent to the model.</li>
 * <li>{@code callerContext} -- the request thread's tracing context, restored
 * around each node; see {@code AgentGraph#inCallerContext}.</li>
 * </ul>
 */
public class ReActState extends AgentState {

	static final String MESSAGES = "messages";
	static final String STEPS = "steps";
	static final String ITERATIONS = "iterations";
	static final String TOOL_CONTEXT = "toolContext";
	static final String CALLER_CONTEXT = "callerContext";

	private static final ContextSnapshot EMPTY_CONTEXT = ContextSnapshotFactory.builder().build().captureFrom();

	static final Map<String, Channel<?>> SCHEMA = Map.of(
			MESSAGES, Channels.<Message>appenderWithDuplicate(ArrayList::new),
			STEPS, Channels.<AgentStep>appenderWithDuplicate(ArrayList::new));

	public ReActState(Map<String, Object> data) {
		super(data);
	}

	public List<Message> messages() {
		return this.<List<Message>>value(MESSAGES).orElse(List.of());
	}

	public List<AgentStep> steps() {
		return this.<List<AgentStep>>value(STEPS).orElse(List.of());
	}

	public int iterations() {
		return this.<Integer>value(ITERATIONS).orElse(0);
	}

	public Map<String, Object> toolContext() {
		return this.<Map<String, Object>>value(TOOL_CONTEXT).orElse(Map.of());
	}

	public ContextSnapshot callerContext() {
		return this.<ContextSnapshot>value(CALLER_CONTEXT).orElse(EMPTY_CONTEXT);
	}

	/** The latest model reply, which is what the router and the Act step look at. */
	public Optional<AssistantMessage> lastAssistant() {
		List<Message> messages = messages();
		if (messages.isEmpty() || !(messages.get(messages.size() - 1) instanceof AssistantMessage last)) {
			return Optional.empty();
		}
		return Optional.of(last);
	}
}

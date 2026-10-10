package com.rootstock.core.agent;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.rootstock.core.agent.dto.AgentStep;
import com.rootstock.core.chat.AiUnavailableException;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * A ReAct agent as a LangGraph4j state graph:
 *
 * <pre>
 * START -> agent --tool calls?--> tools --+
 *            ^                            |
 *            +----------------------------+
 *            | no tool calls, or out of iterations
 *            v
 *           END
 * </pre>
 *
 * <ul>
 * <li><b>agent</b> (Reason) -- one model call with the transcript and the tool
 * definitions. The model either asks for tools or answers.</li>
 * <li><b>tools</b> (Act, Observe) -- runs what was asked for and appends the
 * results, which the next Reason step reads.</li>
 * </ul>
 *
 * <p>The model is called through {@link ChatModel}, not {@code ChatClient}, on
 * purpose: in Spring AI 2 a {@code ChatModel} returns tool calls without running
 * them, while {@code ChatClient} adds an advisor that runs the whole loop
 * internally. That loop is exactly what this graph makes explicit.
 */
@Component
@EnableConfigurationProperties(AgentProperties.class)
public class AgentGraph {

	static final String AGENT = "agent";
	static final String TOOLS = "tools";

	private static final int OBSERVATION_CHARS = 1_000;
	private static final String LAST_ITERATION = "\n\nThis is your last step: no more tool results will "
			+ "come back. Answer now from what you have gathered, and say what you could not confirm.";

	private final ObjectProvider<ChatModel> chatModels;
	private final AgentToolbox toolbox;
	private final AgentProperties properties;
	private final CompiledGraph<ReActState> graph;

	/**
	 * State cloning exists to snapshot state for checkpoints, and it does so by
	 * Java serialization, which Spring AI messages don't support. There is no
	 * checkpointer here and no node mutates the state it is given -- each
	 * returns fresh lists for the channels to merge -- so there is nothing for a
	 * clone to protect.
	 */
	private static final RunnableConfig RUN = RunnableConfig.builder().disableCloneState().build();

	private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

	public AgentGraph(ObjectProvider<ChatModel> chatModels, AgentToolbox toolbox, AgentProperties properties)
			throws GraphStateException {
		this.chatModels = chatModels;
		this.toolbox = toolbox;
		this.properties = properties;
		this.graph = new StateGraph<>(ReActState.SCHEMA, ReActState::new)
				.addNode(AGENT, node_async(inCallerContext(this::reason)))
				.addNode(TOOLS, node_async(inCallerContext(this::act)))
				.addEdge(START, AGENT)
				.addConditionalEdges(AGENT, edge_async(this::route), Map.of(TOOLS, TOOLS, END, END))
				.addEdge(TOOLS, AGENT)
				.compile();
	}

	/**
	 * Runs the agent to completion.
	 *
	 * @param transcript  earlier turns plus the new question, oldest first
	 * @param toolContext what the tools need to know about the caller
	 */
	public ReActState run(List<Message> transcript, Map<String, Object> toolContext) {
		return graph.invoke(Map.of(
						ReActState.MESSAGES, transcript,
						ReActState.TOOL_CONTEXT, toolContext,
						ReActState.CALLER_CONTEXT, SNAPSHOTS.captureAll()), RUN)
				.orElseThrow(() -> new IllegalStateException("Agent graph finished without a state"));
	}

	// ---- nodes ---------------------------------------------------------------

	/** Reason: ask the model what to do next, given everything observed so far. */
	Map<String, Object> reason(ReActState state) {
		int iteration = state.iterations() + 1;
		String system = iteration >= properties.maxIterations()
				? properties.systemPrompt() + LAST_ITERATION
				: properties.systemPrompt();

		List<Message> prompt = new ArrayList<>(state.messages().size() + 1);
		prompt.add(new SystemMessage(system));
		prompt.addAll(state.messages());

		ChatModel model = requireModel();
		// Tools stay declared even on the last step: Bedrock rejects a transcript
		// containing tool calls and results unless the request also declares tools.
		ChatResponse response = model.call(new Prompt(prompt, withTools(model.getDefaultOptions())));
		return Map.of(ReActState.MESSAGES, List.of(merge(response)), ReActState.ITERATIONS, iteration);
	}

	/** Act and Observe: run every tool the model asked for, in order. */
	Map<String, Object> act(ReActState state) {
		AssistantMessage request = state.lastAssistant()
				.orElseThrow(() -> new IllegalStateException("Act step reached without a model reply"));
		ToolContext context = new ToolContext(state.toolContext());
		String thought = StringUtils.hasText(request.getText()) ? request.getText().strip() : null;

		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		List<AgentStep> steps = new ArrayList<>();
		for (AssistantMessage.ToolCall call : request.getToolCalls()) {
			String observation = toolbox.execute(call.name(), call.arguments(), context);
			responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), observation));
			steps.add(new AgentStep(state.iterations(), steps.isEmpty() ? thought : null,
					call.name(), call.arguments(), abbreviate(observation)));
		}
		return Map.of(
				ReActState.MESSAGES, List.of(ToolResponseMessage.builder().responses(responses).build()),
				ReActState.STEPS, steps);
	}

	/** Loop while the model keeps asking for tools and has iterations left. */
	String route(ReActState state) {
		boolean wantsTools = state.lastAssistant().map(AssistantMessage::hasToolCalls).orElse(false);
		return wantsTools && state.iterations() < properties.maxIterations() ? TOOLS : END;
	}

	// ---- helpers -------------------------------------------------------------

	/**
	 * LangGraph4j runs nodes on a pool thread, not the caller's, so the
	 * request's current observation would be lost and every generation and tool
	 * span would start a trace of its own instead of nesting under the request.
	 * Restoring the snapshot taken in {@link #run} puts it back for the node's
	 * duration -- and clears it afterwards, so nothing leaks into the pool.
	 */
	private static NodeAction<ReActState> inCallerContext(NodeAction<ReActState> node) {
		return state -> {
			try (ContextSnapshot.Scope ignored = state.callerContext().setThreadLocals()) {
				return node.apply(state);
			}
		};
	}

	private ToolCallingChatOptions withTools(ChatOptions defaults) {
		// Mutating the model's own defaults keeps the configured model id and
		// temperature (and anything Bedrock-specific) instead of resetting them.
		ToolCallingChatOptions.Builder<?> builder = defaults instanceof ToolCallingChatOptions toolOptions
				? toolOptions.mutate()
				: ToolCallingChatOptions.builder();
		return builder.toolCallbacks(toolbox.callbacks()).build();
	}

	/**
	 * One assistant turn from the whole response. Bedrock reports a reply's text
	 * and its tool calls as separate generations, so taking only the first would
	 * drop the tool calls -- or the thought that came with them.
	 */
	private static AssistantMessage merge(ChatResponse response) {
		StringBuilder text = new StringBuilder();
		List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
		for (Generation generation : response.getResults()) {
			AssistantMessage output = generation.getOutput();
			if (StringUtils.hasText(output.getText())) {
				text.append(text.isEmpty() ? "" : "\n").append(output.getText());
			}
			toolCalls.addAll(output.getToolCalls());
		}
		return AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
	}

	private ChatModel requireModel() {
		ChatModel model = chatModels.getIfAvailable();
		if (model == null) {
			throw new AiUnavailableException(
					"No chat backend is configured. Provide AWS Bedrock credentials and "
							+ "set spring.ai.model.chat=bedrock-converse.");
		}
		return model;
	}

	private static String abbreviate(String text) {
		return text.length() <= OBSERVATION_CHARS ? text : text.substring(0, OBSERVATION_CHARS) + "...";
	}
}

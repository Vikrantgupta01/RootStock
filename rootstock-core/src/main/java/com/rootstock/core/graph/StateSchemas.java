package com.rootstock.core.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.bsc.langgraph4j.state.Reducer;

/** Turns the channels declared in {@code graph.yaml} into LangGraph4j channels. */
public final class StateSchemas {

	private StateSchemas() {
	}

	public static Map<String, Channel<?>> from(GraphDefinition.State state) {
		Map<String, Channel<?>> channels = new HashMap<>();
		state.channels().forEach((name, spec) -> channels.put(name, channel(spec.reducer())));
		return channels;
	}

	static Channel<?> channel(GraphDefinition.Reducer reducer) {
		return switch (reducer) {
			case REPLACE -> Channels.base(REPLACE);
			case MERGE -> Channels.base(MERGE, LinkedHashMap::new);
			// With duplicates: two nodes may add identical audit lines, and both happened.
			case APPEND -> Channels.appenderWithDuplicate(ArrayList::new);
		};
	}

	/** The update replaces what was there. */
	static final Reducer<Object> REPLACE = (current, update) -> update;

	/** Keys from the update are added or replace existing ones; other keys stay. */
	@SuppressWarnings("unchecked")
	static final Reducer<Map<String, Object>> MERGE = (current, update) -> {
		Map<String, Object> merged = new LinkedHashMap<>();
		if (current != null) {
			merged.putAll(current);
		}
		if (update != null) {
			merged.putAll(update);
		}
		return merged;
	};
}

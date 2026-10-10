package com.rootstock.core.graph;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One tool call a tool-calling agent made, and what came of it. Kept in the
 * agent's output channel ({@code {lookups, summary, modelCalls}}) so later
 * steps (rules, reviewers) read what the client system actually said.
 *
 * @param source  {@code plan} or {@code model}
 * @param status  the gateway's status ({@code OK}, {@code BLOCKED}…), {@code REFUSED} for a tool outside the
 *                agent's own list, or {@code SKIPPED} for a plan step missing an argument
 * @param result  the output, parsed when it is JSON; null unless the call succeeded
 * @param message why it did not succeed; null when it did
 */
public record Lookup(String source, String tool, Map<String, Object> arguments, String status, Object result,
		String message) implements Serializable {

	public static final String OK = "OK";

	public boolean ok() {
		return OK.equals(status);
	}

	/** Every lookup in the state: each channel holding an object with a {@code lookups} list. */
	public static List<Lookup> all(CaseState state) {
		List<Lookup> out = new ArrayList<>();
		for (Object value : state.data().values()) {
			if (value instanceof Map<?, ?> m && m.get("lookups") instanceof List<?> list) {
				list.stream().filter(Lookup.class::isInstance).map(Lookup.class::cast).forEach(out::add);
			}
		}
		return out;
	}
}

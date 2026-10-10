package com.rootstock.core.rules;

import com.rootstock.core.graph.CaseState;
import com.rootstock.core.graph.Lookup;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What a rule may look at: the case's state (read by paths such as
 * {@code $.record.needs}), what tool-calling agents looked up, and today's date.
 */
public final class RuleContext {

	private final CaseState state;
	private final List<Lookup> lookups;
	private final LocalDate today;

	public RuleContext(CaseState state, LocalDate today) {
		this.state = state;
		this.lookups = Lookup.all(state);
		this.today = today;
	}

	/** The value at a state path such as {@code $.record.household.suburb}; null when there is none. */
	public Object read(String path) {
		if (path == null || !path.startsWith("$.")) {
			throw new IllegalArgumentException("'" + path + "' is not a state path ($.channel.field)");
		}
		String[] parts = path.substring(2).split("\\.");
		Object current = state.value(parts[0]).orElse(null);
		for (int i = 1; i < parts.length && current != null; i++) {
			current = current instanceof Map<?, ?> m ? m.get(parts[i]) : null;
		}
		return current;
	}

	public List<Lookup> lookups() {
		return lookups;
	}

	public LocalDate today() {
		return today;
	}
}

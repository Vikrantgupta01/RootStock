package com.rootstock.core.cases;

import com.rootstock.core.graph.AuditEntry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Short descriptions of what a node did, for progress events. Never the
 * content itself: events go to screens and traces, and records hold personal
 * details. The node's own audit note when it wrote one, else the channels it changed.
 */
final class Summaries {

	private Summaries() {
	}

	static String of(Map<String, Object> update) {
		if (update.get("audit") instanceof List<?> audit && !audit.isEmpty()
				&& audit.getLast() instanceof AuditEntry entry) {
			return entry.note();
		}
		return update.isEmpty() ? "No changes" : "Updated " + update.keySet().stream().sorted()
				.collect(Collectors.joining(", "));
	}
}

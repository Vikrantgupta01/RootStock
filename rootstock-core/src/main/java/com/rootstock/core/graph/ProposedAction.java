package com.rootstock.core.graph;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A change proposed for the client system (a core.Action). Nothing is done
 * with it until a reviewer approves it and the commit node runs it.
 *
 * @param type    e.g. {@code REFERRAL}, {@code FOLLOW_UP} or {@code CHASE_MESSAGE}
 * @param summary one line a reviewer reads
 * @param details what commit needs to carry it out, e.g. the service's id and the reason
 */
public record ProposedAction(String type, String summary, Map<String, Object> details) implements Serializable {

	public static final String CHASE_MESSAGE = "CHASE_MESSAGE";

	public ProposedAction {
		// A LinkedHashMap, not Map.copyOf: details may hold nulls, and must stay Serializable.
		details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
	}

	public ProposedAction(String type, String summary) {
		this(type, summary, Map.of());
	}
}

package com.rootstock.core.graph;

import java.io.Serializable;

/**
 * A change proposed for the client system (a core.Action). Nothing is done
 * with it until a reviewer approves it and the commit node runs it.
 *
 * @param type e.g. {@code ASSISTANCE} or {@code CHASE_MESSAGE}
 */
public record ProposedAction(String type, String summary) implements Serializable {

	public static final String CHASE_MESSAGE = "CHASE_MESSAGE";
}

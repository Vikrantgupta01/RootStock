package com.rootstock.core.graph;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One line in a case's audit trail: which node did what. */
public record AuditEntry(String node, String note) implements Serializable {

	/** The pack's channel for them; every graph is expected to declare it. */
	public static final String CHANNEL = "audit";

	/** A node's update holding just this entry; add the node's own channels to it. */
	public static Map<String, Object> update(String node, String note) {
		Map<String, Object> update = new LinkedHashMap<>();
		update.put(CHANNEL, List.of(new AuditEntry(node, note)));
		return update;
	}
}

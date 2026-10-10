package com.rootstock.core.graph;

import java.io.Serializable;

/** One line in a case's audit trail: which node did what. */
public record AuditEntry(String node, String note) implements Serializable {
}

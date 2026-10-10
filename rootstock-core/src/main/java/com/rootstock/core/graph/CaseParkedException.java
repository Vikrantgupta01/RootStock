package com.rootstock.core.graph;

/**
 * Thrown by a node that cannot go on without a person, e.g. a model whose
 * output still does not match the schema after its retries. The run stops as
 * PARKED, not FAILED: nothing is broken, the case needs someone to look at it.
 */
public class CaseParkedException extends RuntimeException {

	public CaseParkedException(String reason) {
		super(reason);
	}
}

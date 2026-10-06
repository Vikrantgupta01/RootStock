package com.sinewlabs.vinnies.mcp.vocabulary;

/**
 * The ontology's NeedCategory vocabulary: what a household needs help with, and
 * what a piece of assistance or a local service addresses. Kept in step with the
 * CHECK constraints in db/setup.sql.
 */
public enum NeedCategory {

	/** Food parcels or supermarket vouchers. */
	FOOD,

	/** Help with gas or electricity bills, including disconnection notices. */
	ENERGY_BILL,

	/** Help with rent arrears or bond. */
	RENT
}

package com.rootstock.core.rules;

/**
 * A kind of rule Rootstock knows ({@code limit}, {@code frequency},
 * {@code requires}); a pack's rules.yaml configures instances of it. A Spring
 * bean of this type adds a kind, for a check the built-in kinds cannot express.
 */
public interface RuleKind {

	/** The name rules.yaml uses in {@code kind:}. */
	String name();

	/**
	 * @throws IllegalArgumentException when the spec's settings are wrong for this kind, saying how;
	 *                                  checked at startup
	 */
	Rule create(RuleSpec spec);
}

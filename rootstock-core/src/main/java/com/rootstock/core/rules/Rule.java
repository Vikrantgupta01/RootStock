package com.rootstock.core.rules;

import com.rootstock.core.graph.CaseIssue;
import java.util.List;

/**
 * A deterministic business check on a case: no model, the same answer every
 * time. Built by a {@link RuleKind} from a pack's rules.yaml.
 */
public interface Rule {

	String id();

	/** The issues this rule finds; empty when the case passes it, or when there is nothing to check yet. */
	List<CaseIssue> evaluate(RuleContext context);
}

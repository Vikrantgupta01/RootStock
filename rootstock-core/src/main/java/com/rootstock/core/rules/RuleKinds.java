package com.rootstock.core.rules;

import com.rootstock.core.rules.kinds.FrequencyRule;
import com.rootstock.core.rules.kinds.LimitRule;
import com.rootstock.core.rules.kinds.RequiresRule;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The rule kinds Rootstock provides, plus any added as beans. */
public final class RuleKinds {

	private RuleKinds() {
	}

	public static List<RuleKind> builtIn() {
		return List.of(LimitRule.KIND, FrequencyRule.KIND, RequiresRule.KIND);
	}

	/** By name; an added kind may not reuse a built-in name. */
	public static Map<String, RuleKind> of(Collection<? extends RuleKind> added) {
		Map<String, RuleKind> kinds = new LinkedHashMap<>();
		builtIn().forEach(k -> kinds.put(k.name(), k));
		for (RuleKind k : added) {
			if (kinds.putIfAbsent(k.name(), k) != null) {
				throw new IllegalStateException("Two rule kinds are called '" + k.name() + "'");
			}
		}
		return kinds;
	}
}

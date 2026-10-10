package com.rootstock.core.rules.kinds;

import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.rules.ConfiguredRule;
import com.rootstock.core.rules.Rule;
import com.rootstock.core.rules.RuleContext;
import com.rootstock.core.rules.RuleKind;
import com.rootstock.core.rules.RuleSpec;
import com.rootstock.core.rules.Values;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code kind: requires}: when the case is in some situation, some fields must
 * hold. Every {@code when} condition must hold for the rule to apply; then each
 * {@code require} that fails is one issue, on that field.
 *
 * <pre>
 * - id: R05-new-household
 *   kind: requires
 *   severity: BLOCKING
 *   answerableBy: EXTERNAL
 *   when:
 *     succeeded: [find_household]            # tools with a successful lookup
 *     notSucceeded: [get_assistance_history] # tools without one
 *     present: [$.record.household]          # state paths with a value
 *     equals: { $.record.urgency: HIGH }     # state paths with that value (or one of a list)
 *   require:
 *     $.record.household.contact: present
 *     $.record.household.consentGiven: true
 * </pre>
 *
 * Placeholders for {@code message}: {@code {field}} (the record path) and
 * {@code {expected}}.
 */
public final class RequiresRule extends ConfiguredRule {

	public static final String PRESENT = "present";

	public static final RuleKind KIND = new RuleKind() {

		@Override
		public String name() {
			return "requires";
		}

		@Override
		public Rule create(RuleSpec spec) {
			return new RequiresRule(spec);
		}
	};

	private static final List<String> CONDITIONS = List.of("succeeded", "notSucceeded", "present", "equals");

	private final Map<String, Object> when;
	private final Map<String, Object> require;

	@SuppressWarnings("unchecked")
	RequiresRule(RuleSpec spec) {
		super(spec);
		Object w = spec.config().getOrDefault("when", Map.of());
		if (!(w instanceof Map<?, ?> wm) || !CONDITIONS.containsAll(wm.keySet())) {
			throw new IllegalArgumentException("rule '" + spec.id() + "': 'when' may only have " + CONDITIONS);
		}
		if (!(required(spec, "require") instanceof Map<?, ?> r) || r.isEmpty()
				|| r.keySet().stream().anyMatch(k -> !String.valueOf(k).startsWith("$."))) {
			throw new IllegalArgumentException("rule '" + spec.id() + "': 'require' maps state paths ($.record.x) "
					+ "to 'present' or a value");
		}
		this.when = new LinkedHashMap<>((Map<String, Object>) w);
		this.require = new LinkedHashMap<>((Map<String, Object>) r);
	}

	@Override
	public List<CaseIssue> evaluate(RuleContext context) {
		if (!applies(context)) {
			return List.of();
		}
		List<CaseIssue> out = new ArrayList<>();
		require.forEach((path, expected) -> {
			Object actual = context.read(path);
			boolean ok = PRESENT.equals(expected) ? present(actual) : matches(actual, expected);
			if (!ok) {
				String field = Values.recordPath(path);
				out.add(issue(PRESENT.equals(expected) ? "{field} is required here" : "{field} must be {expected}",
						Map.of("field", field, "expected", expected), field));
			}
		});
		return out;
	}

	private boolean applies(RuleContext context) {
		for (String tool : texts(when.get("succeeded"))) {
			if (context.lookups().stream().noneMatch(l -> l.ok() && l.tool().equals(tool))) {
				return false;
			}
		}
		for (String tool : texts(when.get("notSucceeded"))) {
			if (context.lookups().stream().anyMatch(l -> l.ok() && l.tool().equals(tool))) {
				return false;
			}
		}
		for (String path : texts(when.get("present"))) {
			if (!present(context.read(path))) {
				return false;
			}
		}
		if (when.get("equals") instanceof Map<?, ?> equals) {
			for (Map.Entry<?, ?> e : equals.entrySet()) {
				if (!matches(context.read(String.valueOf(e.getKey())), e.getValue())) {
					return false;
				}
			}
		}
		return true;
	}

	private static List<String> texts(Object value) {
		return value instanceof Collection<?> c ? c.stream().map(String::valueOf).toList()
				: value == null ? List.of() : List.of(String.valueOf(value));
	}

	private static boolean present(Object value) {
		return value != null && !(value instanceof String s && s.isBlank())
				&& !(value instanceof Collection<?> c && c.isEmpty());
	}

	private static boolean matches(Object actual, Object expected) {
		Collection<?> allowed = expected instanceof Collection<?> c ? c : List.of(expected);
		return actual != null && allowed.stream().anyMatch(a -> Objects.equals(String.valueOf(a), String.valueOf(actual)));
	}
}

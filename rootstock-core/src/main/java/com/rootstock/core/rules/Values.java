package com.rootstock.core.rules;

import com.rootstock.core.graph.Lookup;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How rules.yaml names a value. One of:
 *
 * <ul>
 * <li>{@code $.record.household.suburb}: a value in the case state;</li>
 * <li>{@code $item.category}: a field of the list item being checked (with {@code forEach});</li>
 * <li>{@code { lookup: get_assistance_guidelines, match: { assistanceType: $item.category },
 * field: limitPerVisitAud, default: 300 }}: a field of what a successful lookup returned, from the
 * lookup whose arguments match; {@code default} when there is none (a number, or a map keyed by the
 * first {@code match} value, e.g. {@code { FOOD: 150, RENT: 600 }});</li>
 * <li>anything else: the value itself.</li>
 * </ul>
 */
public final class Values {

	private Values() {
	}

	public static Object resolve(Object spec, RuleContext context, Object item) {
		if (spec instanceof String s && s.startsWith("$item.")) {
			return field(item, s.substring("$item.".length()));
		}
		if (spec instanceof String s && s.startsWith("$.")) {
			return context.read(s);
		}
		if (spec instanceof Map<?, ?> m && m.containsKey("lookup")) {
			return lookup(m, context, item);
		}
		return spec;
	}

	private static Object lookup(Map<?, ?> spec, RuleContext context, Object item) {
		String tool = String.valueOf(spec.get("lookup"));
		Map<?, ?> match = spec.get("match") instanceof Map<?, ?> mm ? mm : Map.of();
		for (Lookup l : context.lookups()) {
			if (!l.ok() || !l.tool().equals(tool)) {
				continue;
			}
			boolean matches = match.entrySet().stream().allMatch(e -> Objects.equals(
					String.valueOf(l.arguments().get(String.valueOf(e.getKey()))),
					String.valueOf(resolve(e.getValue(), context, item))));
			if (matches) {
				Object value = spec.get("field") == null ? l.result() : field(l.result(), String.valueOf(spec.get("field")));
				if (value != null) {
					return value;
				}
			}
		}
		Object fallback = spec.get("default");
		if (fallback instanceof Map<?, ?> byKey && !match.isEmpty()) {
			return byKey.get(String.valueOf(resolve(match.values().iterator().next(), context, item)));
		}
		return fallback;
	}

	/** A dotted path into maps, e.g. {@code household.suburb}. */
	public static Object field(Object value, String path) {
		Object current = value;
		for (String key : path.split("\\.")) {
			current = current instanceof Map<?, ?> m ? m.get(key) : null;
		}
		return current;
	}

	/** The items at a state path, or an empty list. */
	public static List<?> items(String path, RuleContext context) {
		return context.read(path) instanceof List<?> l ? l : List.of();
	}

	/** {@code $.record.assistance} → {@code assistance}: an issue's path within the record. */
	public static String recordPath(String statePath) {
		String[] parts = statePath.substring(2).split("\\.", 2);
		return parts.length == 2 ? parts[1] : parts[0];
	}

	/** Fills {@code {name}} placeholders; unknown ones are left as they are. */
	public static String fill(String template, Map<String, ?> values) {
		String out = template;
		for (Map.Entry<String, ?> e : values.entrySet()) {
			out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
		}
		return out;
	}

	/** A number from a value: Number as is, text parsed; null when it is not one. */
	public static BigDecimal number(Object value) {
		if (value instanceof Number n) {
			return new BigDecimal(n.toString());
		}
		if (value instanceof String s) {
			try {
				return new BigDecimal(s.strip());
			}
			catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}
}

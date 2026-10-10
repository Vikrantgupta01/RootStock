package com.rootstock.core.rules.kinds;

import java.util.LinkedHashMap;
import java.util.Map;

final class Items {

	private Items() {
	}

	/** {@code {item.<field>}} for each field of a list item. */
	static Map<String, Object> placeholders(Object item) {
		Map<String, Object> values = new LinkedHashMap<>();
		if (item instanceof Map<?, ?> m) {
			m.forEach((k, v) -> values.put("item." + k, v));
		}
		return values;
	}
}

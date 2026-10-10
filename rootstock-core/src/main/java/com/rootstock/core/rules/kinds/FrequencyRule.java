package com.rootstock.core.rules.kinds;

import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.graph.Lookup;
import com.rootstock.core.rules.ConfiguredRule;
import com.rootstock.core.rules.Rule;
import com.rootstock.core.rules.RuleContext;
import com.rootstock.core.rules.RuleKind;
import com.rootstock.core.rules.RuleSpec;
import com.rootstock.core.rules.Values;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code kind: frequency}: the same thing must not have happened within a
 * window before, according to a history a lookup returned.
 *
 * <pre>
 * - id: R03-frequency
 *   kind: frequency
 *   severity: WARNING
 *   forEach: $.record.needs              # what is asked for now
 *   key: category                        # the field to compare
 *   history: { lookup: get_assistance_history, list: assistance, key: type, date: date }
 *   window: { lookup: get_assistance_guidelines, match: { assistanceType: $item.category },
 *             field: repeatWindowDays, default: 90 }
 *   since: $.record.visitDate            # optional; today when absent or unknown
 * </pre>
 *
 * Placeholders for {@code message}: {@code {key}}, {@code {window}},
 * {@code {date}} (the most recent one inside the window), {@code {count}} and
 * {@code {item.<field>}}. Without a successful history lookup there is nothing
 * to check, so nothing is reported.
 */
public final class FrequencyRule extends ConfiguredRule {

	public static final RuleKind KIND = new RuleKind() {

		@Override
		public String name() {
			return "frequency";
		}

		@Override
		public Rule create(RuleSpec spec) {
			return new FrequencyRule(spec);
		}
	};

	private record Entry(String key, LocalDate date) {
	}

	private final String forEach;
	private final String key;
	private final String historyTool;
	private final String historyList;
	private final String historyKey;
	private final String historyDate;
	private final Object window;
	private final Object since;

	FrequencyRule(RuleSpec spec) {
		super(spec);
		this.forEach = statePath(spec, "forEach");
		this.key = requiredText(spec, "key");
		if (!(required(spec, "history") instanceof Map<?, ?> history) || history.get("lookup") == null
				|| history.get("key") == null || history.get("date") == null) {
			throw new IllegalArgumentException("rule '" + spec.id() + "': 'history' needs lookup, key and date "
					+ "(and list, when the entries are a field of the result)");
		}
		this.historyTool = String.valueOf(history.get("lookup"));
		this.historyList = history.get("list") == null ? null : String.valueOf(history.get("list"));
		this.historyKey = String.valueOf(history.get("key"));
		this.historyDate = String.valueOf(history.get("date"));
		this.window = required(spec, "window");
		this.since = spec.config().get("since");
	}

	@Override
	public List<CaseIssue> evaluate(RuleContext context) {
		List<Lookup> histories = context.lookups().stream().filter(l -> l.ok() && l.tool().equals(historyTool)).toList();
		if (histories.isEmpty()) {
			return List.of();
		}
		List<Entry> entries = new ArrayList<>();
		for (Lookup l : histories) {
			Object list = historyList == null ? l.result() : Values.field(l.result(), historyList);
			if (list instanceof List<?> items) {
				for (Object e : items) {
					LocalDate date = date(Values.field(e, historyDate));
					Object k = Values.field(e, historyKey);
					if (date != null && k != null) {
						entries.add(new Entry(String.valueOf(k), date));
					}
				}
			}
		}
		LocalDate reference = Optional.ofNullable(since == null ? null : date(Values.resolve(since, context, null)))
				.orElse(context.today());

		List<CaseIssue> out = new ArrayList<>();
		List<?> items = Values.items(forEach, context);
		Set<String> reported = new HashSet<>();
		for (int i = 0; i < items.size(); i++) {
			Object item = items.get(i);
			Object k = Values.field(item, key);
			BigDecimal days = Values.number(Values.resolve(window, context, item));
			if (k == null || days == null || !reported.add(String.valueOf(k))) {
				continue;
			}
			LocalDate from = reference.minusDays(days.longValue());
			List<Entry> repeats = entries.stream().filter(e -> Objects.equals(e.key(), String.valueOf(k)))
					.filter(e -> e.date().isAfter(from) && !e.date().isAfter(reference))
					.sorted(Comparator.comparing(Entry::date).reversed()).toList();
			if (repeats.isEmpty()) {
				continue;
			}
			Map<String, Object> values = Items.placeholders(item);
			values.put("key", k);
			values.put("window", days.stripTrailingZeros().toPlainString());
			values.put("date", repeats.getFirst().date());
			values.put("count", repeats.size());
			out.add(issue("{key} was already given on {date}, within the {window}-day window", values,
					Values.recordPath(forEach) + "[" + i + "]." + key));
		}
		return out;
	}

	private static LocalDate date(Object value) {
		if (value instanceof LocalDate d) {
			return d;
		}
		if (value == null) {
			return null;
		}
		try {
			return LocalDate.parse(String.valueOf(value));
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}
}

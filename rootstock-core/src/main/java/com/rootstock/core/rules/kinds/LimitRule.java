package com.rootstock.core.rules.kinds;

import com.rootstock.core.graph.CaseIssue;
import com.rootstock.core.rules.ConfiguredRule;
import com.rootstock.core.rules.Rule;
import com.rootstock.core.rules.RuleContext;
import com.rootstock.core.rules.RuleKind;
import com.rootstock.core.rules.RuleSpec;
import com.rootstock.core.rules.Values;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code kind: limit}: an amount on each item must not be over a limit.
 *
 * <pre>
 * - id: R02-amount-limit
 *   kind: limit
 *   severity: WARNING
 *   forEach: $.record.assistance        # the items
 *   amount: amountAud                   # the field holding each item's amount
 *   limit: { lookup: get_assistance_guidelines, match: { assistanceType: $item.category },
 *            field: limitPerVisitAud, default: { FOOD: 150 } }
 * </pre>
 *
 * Placeholders for {@code message}: {@code {amount}}, {@code {limit}} and
 * {@code {item.<field>}}. An item with no amount, or no limit to compare
 * with, is not checked.
 */
public final class LimitRule extends ConfiguredRule {

	public static final RuleKind KIND = new RuleKind() {

		@Override
		public String name() {
			return "limit";
		}

		@Override
		public Rule create(RuleSpec spec) {
			return new LimitRule(spec);
		}
	};

	private final String forEach;
	private final String amount;
	private final Object limit;

	LimitRule(RuleSpec spec) {
		super(spec);
		this.forEach = statePath(spec, "forEach");
		this.amount = requiredText(spec, "amount");
		this.limit = required(spec, "limit");
	}

	@Override
	public List<CaseIssue> evaluate(RuleContext context) {
		List<CaseIssue> out = new ArrayList<>();
		List<?> items = Values.items(forEach, context);
		for (int i = 0; i < items.size(); i++) {
			Object item = items.get(i);
			BigDecimal value = Values.number(Values.field(item, amount));
			BigDecimal max = Values.number(Values.resolve(limit, context, item));
			if (value == null || max == null || value.compareTo(max) <= 0) {
				continue;
			}
			Map<String, Object> values = Items.placeholders(item);
			values.put("amount", value.stripTrailingZeros().toPlainString());
			values.put("limit", max.stripTrailingZeros().toPlainString());
			out.add(issue("{amount} is over the limit of {limit}", values,
					Values.recordPath(forEach) + "[" + i + "]." + amount));
		}
		return out;
	}
}

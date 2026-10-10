package com.rootstock.core.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * The small, fixed condition language of {@code route: - when: {...}}. No
 * expressions and no scripting, so a graph definition cannot run code. Each key
 * is {@code <channel>.<something>}:
 *
 * <ul>
 * <li>{@code issues.anySeverity: BLOCKING}, {@code issues.answerableBy: SUBMITTER},
 * {@code actions.includesType: CHASE_MESSAGE}, or generally
 * {@code <list>.any.<field>: value}: some item has that value. Several of these on
 * the same list in one {@code when} must hold for the <em>same</em> item, so
 * "a BLOCKING issue the submitter can answer" means exactly that.</li>
 * <li>{@code issues.countAbove: 2}: the list has more than 2 items.</li>
 * <li>{@code issues.unseenBy: review}: some item was not among those a person saw when they decided
 * (the {@code seen} list in that channel, e.g. the issues a reviewer was shown); see {@link #signature}.</li>
 * <li>{@code record.household.exists: true}: the value is (or is not) there.</li>
 * <li>{@code review.decision: APPROVED}: the value equals one of the given values.</li>
 * </ul>
 *
 * All conditions in one {@code when} must hold. Routes are tried in order; the
 * {@code default} is taken when none matches.
 */
public final class RouteConditions {

	/** Shorthands for "some item's field equals". */
	static final Map<String, String> ITEM_FIELDS = Map.of(
			"anySeverity", "severity",
			"answerableBy", "answerableBy",
			"includesType", "type");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private RouteConditions() {
	}

	sealed interface Condition permits ItemMatch, CountAbove, Exists, Equals, UnseenBy {
	}

	record UnseenBy(String channel, String seenIn) implements Condition {
	}

	/** The key {@code unseenBy} compares items by: an issue's rule and field. */
	public static String signature(Object item) {
		Map<String, Object> m = asMap(item);
		return m.get("ruleId") + "|" + (m.get("path") == null ? "" : m.get("path"));
	}

	record ItemMatch(String channel, String field, Set<String> values) implements Condition {
	}

	record CountAbove(String channel, int count) implements Condition {
	}

	record Exists(String channel, List<String> path, boolean expected) implements Condition {
	}

	record Equals(String channel, List<String> path, Set<String> values) implements Condition {
	}

	/**
	 * Parses one condition, or explains why it can't be.
	 *
	 * @param lists   channels that may hold a list (append or replace reducer)
	 * @param objects channels that may hold an object (merge or replace reducer, and the engine's own)
	 * @param known   every channel a condition may name
	 */
	static Condition parse(String key, Object value, Set<String> lists, Set<String> objects, Set<String> known) {
		String[] parts = key.split("\\.");
		String channel = parts[0];
		if (!known.contains(channel)) {
			throw new IllegalArgumentException("'" + key + "': unknown state channel '" + channel + "'; known: "
					+ known.stream().sorted().toList());
		}
		if (parts.length < 2) {
			throw new IllegalArgumentException("'" + key + "' should be <channel>.<field or test>, e.g. "
					+ "review.decision or issues.anySeverity");
		}
		String test = parts[1];
		boolean list = lists.contains(channel);
		boolean object = objects.contains(channel);
		if (test.equals("countAbove")) {
			if (!list || parts.length != 2) {
				throw new IllegalArgumentException("'" + key + "': countAbove works on a list channel, as <list>.countAbove");
			}
			if (!(value instanceof Integer n) || n < 0) {
				throw new IllegalArgumentException("'" + key + "': countAbove needs a whole number, found '" + value + "'");
			}
			return new CountAbove(channel, n);
		}
		if (test.equals("unseenBy")) {
			if (!list || parts.length != 2) {
				throw new IllegalArgumentException("'" + key + "': unseenBy works on a list channel, as <list>.unseenBy");
			}
			if (!(value instanceof String seenIn) || !objects.contains(seenIn)) {
				throw new IllegalArgumentException("'" + key + "': unseenBy names the channel holding what was seen, "
						+ "e.g. review; '" + value + "' is not one");
			}
			return new UnseenBy(channel, seenIn);
		}
		if (ITEM_FIELDS.containsKey(test) || test.equals("any")) {
			if (!list) {
				throw new IllegalArgumentException("'" + key + "': '" + test + "' works on a list channel; '"
						+ channel + "' is not one");
			}
			String field = test.equals("any") ? (parts.length == 3 ? parts[2] : null) : ITEM_FIELDS.get(test);
			if (field == null || (!test.equals("any") && parts.length != 2)) {
				throw new IllegalArgumentException("'" + key + "' should be <list>." + test
						+ (test.equals("any") ? ".<field>" : ""));
			}
			return new ItemMatch(channel, field, values(value));
		}
		if (!object) {
			throw new IllegalArgumentException("'" + key + "': '" + channel + "' is a list; use one of "
					+ ITEM_FIELDS.keySet().stream().sorted().toList() + ", any.<field> or countAbove");
		}
		List<String> path = List.of(parts).subList(1, parts.length);
		if (path.getLast().equals("exists")) {
			if (!(value instanceof Boolean b)) {
				throw new IllegalArgumentException("'" + key + "': exists needs true or false, found '" + value + "'");
			}
			return new Exists(channel, path.subList(0, path.size() - 1), b);
		}
		return new Equals(channel, path, values(value));
	}

	/**
	 * Compiles a route into a function from state to the next node.
	 *
	 * @throws IllegalArgumentException if a condition is malformed; the validator reports these first
	 */
	public static Function<CaseState, String> compile(List<GraphDefinition.RouteSpec> route, Set<String> lists,
			Set<String> objects, Set<String> known) {
		record Branch(List<Condition> conditions, String target) {
		}
		List<Branch> branches = new ArrayList<>();
		String fallback = null;
		for (GraphDefinition.RouteSpec spec : route) {
			if (spec.isDefault()) {
				fallback = spec.defaultTarget();
				continue;
			}
			List<Condition> conditions = new ArrayList<>();
			spec.when().forEach((k, v) -> conditions.add(parse(k, v, lists, objects, known)));
			branches.add(new Branch(conditions, spec.to()));
		}
		String otherwise = fallback;
		return state -> branches.stream().filter(b -> holds(b.conditions(), state)).map(Branch::target).findFirst()
				.orElse(otherwise);
	}

	/**
	 * How the case stands after a run of this graph ended after {@code lastNode}:
	 * the first of its outcomes whose {@code when} holds; empty when the graph
	 * says nothing about that node.
	 */
	public static Optional<GraphDefinition.Outcome> outcome(GraphDefinition g, String lastNode,
			CaseState state) {
		Set<String> lists = GraphValidator.lists(g);
		Set<String> objects = GraphValidator.objects(g);
		Set<String> known = GraphValidator.channels(g);
		for (GraphDefinition.Outcome o : g.outcomes().getOrDefault(lastNode, List.of())) {
			List<Condition> conditions = new ArrayList<>();
			o.when().forEach((k, v) -> conditions.add(parse(k, v, lists, objects, known)));
			if (holds(conditions, state)) {
				return Optional.of(o);
			}
		}
		return Optional.empty();
	}

	static boolean holds(List<Condition> conditions, CaseState state) {
		Map<String, List<ItemMatch>> itemMatches = new LinkedHashMap<>();
		for (Condition c : conditions) {
			switch (c) {
				case ItemMatch m -> itemMatches.computeIfAbsent(m.channel(), k -> new ArrayList<>()).add(m);
				case CountAbove n -> {
					if (state.list(n.channel()).size() <= n.count()) {
						return false;
					}
				}
				case Exists e -> {
					if (lookup(state, e.channel(), e.path()).isPresent() != e.expected()) {
						return false;
					}
				}
				case UnseenBy u -> {
					Object seen = asMap(state.value(u.seenIn()).orElse(null)).get("seen");
					Set<String> shown = seen instanceof List<?> l
							? l.stream().map(String::valueOf).collect(Collectors.toSet()) : Set.of();
					if (state.list(u.channel()).stream().map(RouteConditions::signature).allMatch(shown::contains)) {
						return false;
					}
				}
				case Equals q -> {
					Optional<Object> v = lookup(state, q.channel(), q.path());
					if (v.isEmpty() || !q.values().contains(String.valueOf(v.get()))) {
						return false;
					}
				}
			}
		}
		for (Map.Entry<String, List<ItemMatch>> entry : itemMatches.entrySet()) {
			boolean someItemMatchesAll = state.list(entry.getKey()).stream().map(RouteConditions::asMap)
					.anyMatch(item -> entry.getValue().stream()
							.allMatch(m -> m.values().contains(String.valueOf(item.get(m.field())))));
			if (!someItemMatchesAll) {
				return false;
			}
		}
		return true;
	}

	private static Optional<Object> lookup(CaseState state, String channel, List<String> path) {
		Object current = state.value(channel).orElse(null);
		for (String key : path) {
			if (current == null) {
				return Optional.empty();
			}
			current = asMap(current).get(key);
		}
		return Optional.ofNullable(current);
	}

	/** Records and maps alike, so conditions read fields by name. */
	@SuppressWarnings("unchecked")
	static Map<String, Object> asMap(Object value) {
		if (value instanceof Map<?, ?> m) {
			return (Map<String, Object>) m;
		}
		if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
			return Map.of();
		}
		return JSON.convertValue(value, Map.class);
	}

	private static Set<String> values(Object value) {
		List<?> items = value instanceof List<?> l ? l : List.of(value);
		return items.stream().map(String::valueOf).collect(Collectors.toSet());
	}
}

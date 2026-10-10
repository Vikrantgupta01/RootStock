package com.rootstock.core.ontology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks a record against the ontology's constraints (when these fields hold,
 * those must too), for every instance of the constrained entity the record
 * holds: the root, and each embedded object of that entity.
 *
 * <p>A required value the record does not have at all is not a violation here:
 * the field is missing, which {@link RequiredFields} reports. So "risk flags
 * require HIGH urgency" is broken by {@code urgency: LOW}, not by no urgency.
 */
public final class ConstraintChecker {

	/**
	 * @param path the instance's place in the record, e.g. {@code ""} for the root or {@code needs[1]}
	 * @param failed the require conditions that do not hold, e.g. {@code urgency} (as {@code Entity.field})
	 */
	public record Violation(Constraint constraint, String path, List<String> failed) {
	}

	private final JsonSchemaGenerator generator = new JsonSchemaGenerator();

	public List<Violation> check(ResolvedOntology o, String projection, Object record) {
		Map<String, Object> schema = generator.generate(o, projection, JsonSchemaGenerator.Mode.COMPLETE);
		Map<String, List<Instance>> byEntity = new LinkedHashMap<>();
		collect(schema, record, "", map(schema.get("$defs")), byEntity);

		List<Violation> out = new ArrayList<>();
		for (Constraint c : o.own().constraints()) {
			String entity = entityOf(c);
			if (entity == null) {
				continue;
			}
			for (Instance instance : byEntity.getOrDefault(entity, List.of())) {
				if (!c.when().entrySet().stream()
						.allMatch(e -> holds(instance.value(), field(o, e.getKey()), e.getValue()))) {
					continue;
				}
				List<String> failed = new ArrayList<>();
				c.require().forEach((path, expected) -> {
					Object actual = instance.value().get(field(o, path));
					boolean unknown = actual == null && !Constraint.EMPTY.equals(expected);
					if (!unknown && !holds(instance.value(), field(o, path), expected)) {
						failed.add(path);
					}
				});
				if (!failed.isEmpty()) {
					out.add(new Violation(c, instance.path(), failed));
				}
			}
		}
		return out;
	}

	private record Instance(String path, Map<String, Object> value) {
	}

	/** Every object in the record, under the entity its schema is titled with. */
	private static void collect(Map<String, Object> schema, Object value, String path, Map<String, Object> defs,
			Map<String, List<Instance>> out) {
		Map<String, Object> s = schema;
		if (s.get("$ref") instanceof String ref) {
			s = map(defs.get(ref.substring(ref.lastIndexOf('/') + 1)));
		}
		if ("array".equals(s.get("type")) && value instanceof List<?> items) {
			for (int i = 0; i < items.size(); i++) {
				collect(map(s.get("items")), items.get(i), path + "[" + i + "]", defs, out);
			}
			return;
		}
		if (!"object".equals(s.get("type")) || !(value instanceof Map<?, ?> object)) {
			return;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> fields = (Map<String, Object>) object;
		if (s.get("title") instanceof String title) {
			out.computeIfAbsent(title, k -> new ArrayList<>()).add(new Instance(path, fields));
		}
		map(s.get("properties")).forEach((name, property) -> {
			Object v = fields.get(name);
			if (v != null) {
				collect(map(property), v, path.isEmpty() ? name : path + "." + name, defs, out);
			}
		});
	}

	/** The single entity a constraint is about; null when its paths span several (not checked). */
	private static String entityOf(Constraint c) {
		List<String> entities = new ArrayList<>();
		c.when().keySet().forEach(p -> entities.add(p.split("\\.")[0]));
		c.require().keySet().forEach(p -> entities.add(p.split("\\.")[0]));
		return entities.stream().distinct().count() == 1 ? entities.getFirst() : null;
	}

	/** {@code Case.urgency} → {@code urgency}; a relation's name → the record field it fills ({@code identifies} → {@code needs}). */
	private static String field(ResolvedOntology o, String path) {
		String[] parts = path.split("\\.", 2);
		return o.entity(parts[0]).flatMap(e -> o.relation(e, parts[1])).map(r -> r.item().fieldOrName())
				.orElse(parts[1]);
	}

	static boolean holds(Map<String, Object> instance, String field, Object expected) {
		Object actual = instance.get(field);
		if (Constraint.NOT_EMPTY.equals(expected)) {
			return !empty(actual);
		}
		if (Constraint.EMPTY.equals(expected)) {
			return empty(actual);
		}
		Collection<?> allowed = expected instanceof Collection<?> list ? list : List.of(expected);
		if (actual instanceof Collection<?> values) {
			// A many-valued field holds a code when it contains it.
			return values.stream().anyMatch(v -> allowed.stream().anyMatch(a -> same(a, v)));
		}
		return actual != null && allowed.stream().anyMatch(a -> same(a, actual));
	}

	private static boolean same(Object a, Object b) {
		return Objects.equals(String.valueOf(a), String.valueOf(b));
	}

	private static boolean empty(Object value) {
		return value == null || value instanceof Collection<?> c && c.isEmpty()
				|| value instanceof String s && s.isBlank();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}
}

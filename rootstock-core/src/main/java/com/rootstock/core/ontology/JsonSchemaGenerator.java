package com.rootstock.core.ontology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates the JSON Schema of a projection: the shape an agent's structured
 * output must have. Starting at the root entity, attributes become properties
 * and vocabularies become enums described by their definitions. A relation to
 * an embedded entity becomes a nested object (defined once under
 * {@code $defs}); one to a referenced entity becomes its id. Relations to
 * anything else are left out, which also keeps unneeded personal data away
 * from the model.
 *
 * <p>An embedded entity is placed where the first relation from the root
 * reaches it; later relations to it (such as Assistance back to the Need it
 * meets) are left out, so the record has no cycles or duplicates.
 *
 * <p>Expects a validated ontology.
 */
public final class JsonSchemaGenerator {

	public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	public Map<String, Object> generate(ResolvedOntology o, String projectionName) {
		Projection p = o.own().projections().get(projectionName);
		if (p == null) {
			throw new IllegalArgumentException("No projection '" + projectionName + "' in " + o.own().name()
					+ "; known: " + o.own().projections().keySet());
		}
		Entity root = o.entity(p.root()).orElseThrow();
		Set<Entity> embed = p.embed().stream().map(n -> o.entity(n).orElseThrow()).collect(Collectors.toSet());
		Set<Entity> reference = p.reference().stream().map(n -> o.entity(n).orElseThrow()).collect(Collectors.toSet());
		Map<Entity, ResolvedOntology.Declared<Relation>> placement = placements(o, root, embed);

		Map<String, Set<String>> include = new LinkedHashMap<>();
		for (String path : p.include()) {
			String[] parts = path.split("\\.", 2);
			Set<String> sub = include.computeIfAbsent(parts[0], k -> new HashSet<>());
			if (parts.length == 2) {
				sub.add(parts[1]);
			}
		}

		Map<String, Object> defs = new LinkedHashMap<>();
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("$schema", DIALECT);
		schema.put("title", root.name());
		putIfText(schema, "description", root.description());
		schema.putAll(object(o, root, embed, reference, placement, include.isEmpty() ? null : include, defs));
		if (!defs.isEmpty()) {
			schema.put("$defs", defs);
		}
		return schema;
	}

	public String generateJson(ResolvedOntology o, String projectionName) {
		return JSON.writeValueAsString(generate(o, projectionName)) + "\n";
	}

	/** For each embedded entity, the relation that first reaches it, breadth first from the root. */
	private static Map<Entity, ResolvedOntology.Declared<Relation>> placements(ResolvedOntology o, Entity root,
			Set<Entity> embed) {
		Map<Entity, ResolvedOntology.Declared<Relation>> placement = new HashMap<>();
		Deque<Entity> queue = new ArrayDeque<>(List.of(root));
		Set<Entity> visited = new HashSet<>();
		while (!queue.isEmpty()) {
			Entity current = queue.poll();
			if (!visited.add(current)) {
				continue;
			}
			for (ResolvedOntology.Declared<Relation> r : o.relations(current)) {
				Entity target = o.target(r).orElseThrow();
				if (embed.contains(target) && target != root && !placement.containsKey(target)) {
					placement.put(target, owned(r, current));
					queue.add(target);
				}
			}
		}
		return placement;
	}

	/** The relation as seen from the entity being rendered (which may inherit it). */
	private static ResolvedOntology.Declared<Relation> owned(ResolvedOntology.Declared<Relation> r, Entity from) {
		return new ResolvedOntology.Declared<>(r.item(), from);
	}

	/**
	 * @param include null for every field; otherwise field -> sub-fields (empty: all of that field)
	 */
	private Map<String, Object> object(ResolvedOntology o, Entity entity, Set<Entity> embed, Set<Entity> reference,
			Map<Entity, ResolvedOntology.Declared<Relation>> placement, Map<String, Set<String>> include,
			Map<String, Object> defs) {
		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> required = new ArrayList<>();

		for (ResolvedOntology.Declared<Attribute> d : o.attributes(entity)) {
			Attribute a = d.item();
			if (include != null && !include.containsKey(a.name())) {
				continue;
			}
			properties.put(a.name(), attribute(o, d));
			if (a.required()) {
				required.add(a.name());
			}
		}
		for (ResolvedOntology.Declared<Relation> d : o.relations(entity)) {
			Relation r = d.item();
			Entity target = o.target(d).orElseThrow();
			String field = r.fieldOrName();
			if (reference.contains(target)) {
				// Always kept, even when include narrows the fields: a projection lists
				// what it references on purpose, and an id carries no personal data.
				String name = field + (r.many() ? "Refs" : "Ref");
				Map<String, Object> id = new LinkedHashMap<>();
				id.put("type", "string");
				id.put("description", "Id of the " + target.name() + " in the client system"
						+ (r.description() == null ? "" : ". " + r.description()));
				properties.put(name, many(r, id, null));
				if (r.required()) {
					required.add(name);
				}
			}
			else if (embed.contains(target) && sameRelation(placement.get(target), d, entity)) {
				if (include != null && !include.containsKey(field)) {
					continue;
				}
				if (!defs.containsKey(target.name())) {
					defs.put(target.name(), Map.of()); // reserve the slot (and its order) before recursing
					Map<String, Set<String>> sub = include == null || include.get(field).isEmpty() ? null
							: include.get(field).stream().collect(Collectors.toMap(f -> f, f -> new HashSet<>()));
					Map<String, Object> def = new LinkedHashMap<>();
					def.put("title", target.name());
					putIfText(def, "description", target.description());
					def.putAll(object(o, target, embed, reference, placement, sub, defs));
					defs.put(target.name(), def);
				}
				properties.put(field, many(r, Map.of("$ref", "#/$defs/" + target.name()), r.description()));
				if (r.required() || (r.min() != null && r.min() > 0)) {
					required.add(field);
				}
			}
		}

		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("type", "object");
		shape.put("additionalProperties", false);
		if (!required.isEmpty()) {
			shape.put("required", required);
		}
		shape.put("properties", properties);
		return shape;
	}

	private static boolean sameRelation(ResolvedOntology.Declared<Relation> placed, ResolvedOntology.Declared<Relation> d,
			Entity from) {
		return placed != null && placed.declaredBy() == from && placed.item() == d.item();
	}

	private Map<String, Object> attribute(ResolvedOntology o, ResolvedOntology.Declared<Attribute> d) {
		Attribute a = d.item();
		Map<String, Object> value = new LinkedHashMap<>();
		String description = a.description();
		if (a.vocab() != null) {
			Vocabulary vocab = o.vocabulary(d).orElseThrow();
			value.put("type", "string");
			value.put("enum", vocab.codes());
			String meanings = vocab.terms().stream()
					.map(t -> t.code() + (t.definition() == null ? "" : ": " + withoutFullStop(t.definition())))
					.collect(Collectors.joining("; ", "", "."));
			description = description == null ? meanings : description + " " + meanings;
		}
		else {
			switch (a.type()) {
				case "decimal" -> value.put("type", "number");
				case "date" -> {
					value.put("type", "string");
					value.put("format", "date");
				}
				case "datetime" -> {
					value.put("type", "string");
					value.put("format", "date-time");
				}
				default -> value.put("type", a.type());
			}
			if (a.min() != null) {
				value.put("minimum", a.min());
			}
			if (a.max() != null) {
				value.put("maximum", a.max());
			}
		}
		if (a.many()) {
			Map<String, Object> array = new LinkedHashMap<>();
			array.put("type", "array");
			putIfText(array, "description", description);
			array.put("items", value);
			return array;
		}
		putIfText(value, "description", description);
		return value;
	}

	private static Map<String, Object> many(Relation r, Map<String, Object> item, String description) {
		if (!r.many()) {
			if (description == null) {
				return item;
			}
			Map<String, Object> described = new LinkedHashMap<>(item);
			described.put("description", description);
			return described;
		}
		Map<String, Object> array = new LinkedHashMap<>();
		array.put("type", "array");
		putIfText(array, "description", description);
		if (r.min() != null) {
			array.put("minItems", r.min());
		}
		if (r.max() != null) {
			array.put("maxItems", r.max());
		}
		array.put("items", item);
		return array;
	}

	private static String withoutFullStop(String text) {
		String t = text.strip();
		return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
	}

	private static void putIfText(Map<String, Object> map, String key, String text) {
		if (text != null && !text.isBlank()) {
			map.put(key, text.strip());
		}
	}
}

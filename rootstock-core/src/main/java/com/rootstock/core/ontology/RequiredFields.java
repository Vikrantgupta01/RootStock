package com.rootstock.core.ontology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a record extracted in {@link JsonSchemaGenerator.Mode#EXTRACTION} mode
 * still lacks: every field the ontology requires that is null, absent, or (for
 * a list with a minimum) too short. Ids in the client system are not counted;
 * extraction never fills them.
 *
 * <p>Only the record's shape is assumed to be roughly right; anything that is
 * not an object or list where one is expected is skipped, not reported (shape
 * is the extraction schema's job).
 */
public final class RequiredFields {

	/**
	 * @param path        where it is, e.g. {@code visitDate} or {@code needs[0].category}
	 * @param description what the field means, from the ontology; null if it has none
	 */
	public record Missing(String path, String description) {
	}

	private final JsonSchemaGenerator generator = new JsonSchemaGenerator();

	public List<Missing> missing(ResolvedOntology o, String projection, Object record) {
		Map<String, Object> schema = generator.generate(o, projection, JsonSchemaGenerator.Mode.COMPLETE);
		Map<String, Object> defs = map(schema.get("$defs"));
		List<Missing> out = new ArrayList<>();
		check(schema, record, "", defs, out);
		return out;
	}

	private static void check(Map<String, Object> schema, Object value, String path, Map<String, Object> defs,
			List<Missing> out) {
		Map<String, Object> s = resolve(schema, defs);
		if ("array".equals(s.get("type")) && value instanceof List<?> items) {
			for (int i = 0; i < items.size(); i++) {
				check(map(s.get("items")), items.get(i), path + "[" + i + "]", defs, out);
			}
			return;
		}
		if (!"object".equals(s.get("type")) || !(value instanceof Map<?, ?> object)) {
			return;
		}
		Map<String, Object> properties = map(s.get("properties"));
		List<?> required = s.get("required") instanceof List<?> r ? r : List.of();
		properties.forEach((name, propertySchema) -> {
			Map<String, Object> property = map(propertySchema);
			Object v = object.get(name);
			String at = path.isEmpty() ? name : path + "." + name;
			if (required.contains(name)) {
				int min = property.get("minItems") instanceof Integer n ? n : 0;
				if (v == null || (v instanceof List<?> l && l.size() < min)) {
					out.add(new Missing(at, description(property, defs)));
					return;
				}
			}
			if (v != null) {
				check(property, v, at, defs, out);
			}
		});
	}

	private static String description(Map<String, Object> property, Map<String, Object> defs) {
		if (property.get("description") instanceof String d) {
			return d;
		}
		Map<String, Object> target = resolve("array".equals(property.get("type")) ? map(property.get("items")) : property,
				defs);
		return target.get("description") instanceof String d ? d : null;
	}

	private static Map<String, Object> resolve(Map<String, Object> schema, Map<String, Object> defs) {
		if (schema.get("$ref") instanceof String ref) {
			return map(defs.get(ref.substring(ref.lastIndexOf('/') + 1)));
		}
		return schema;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
	}
}

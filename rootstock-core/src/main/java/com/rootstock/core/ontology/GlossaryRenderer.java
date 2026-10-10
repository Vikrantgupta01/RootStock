package com.rootstock.core.ontology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the vocabularies an agent works with as plain text for its prompt:
 * each code with its definition and the other words people use for it, so
 * messy wording ("power bill", "behind on rent") maps to the right code. For a
 * projection, only the vocabularies its fields use; otherwise all of them.
 *
 * <p>Expects a validated ontology.
 */
public final class GlossaryRenderer {

	/** Vocabularies used by the projection's schema, in the order its fields first use them. */
	public String render(ResolvedOntology o, String projectionName) {
		Map<String, Object> schema = new JsonSchemaGenerator().generate(o, projectionName);
		Map<Vocabulary, List<String>> used = new LinkedHashMap<>();
		Entity root = o.entity(o.own().projections().get(projectionName).root()).orElseThrow();
		collect(o, root, schema, used);
		@SuppressWarnings("unchecked")
		Map<String, Object> defs = (Map<String, Object>) schema.getOrDefault("$defs", Map.of());
		for (String defName : defs.keySet()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> def = (Map<String, Object>) defs.get(defName);
			Entity entity = o.entity(defName).or(() -> o.entity(ResolvedOntology.CORE_PREFIX + defName)).orElseThrow();
			collect(o, entity, def, used);
		}
		return text(o, "projection " + projectionName, used);
	}

	/** Every vocabulary of the ontology (its own, then the core's that it uses). */
	public String renderAll(ResolvedOntology o) {
		Map<Vocabulary, List<String>> used = new LinkedHashMap<>();
		o.own().vocabularies().values().forEach(v -> used.put(v, new ArrayList<>()));
		for (Entity e : o.own().entities().values()) {
			for (ResolvedOntology.Declared<Attribute> d : o.attributes(e)) {
				o.vocabulary(d).ifPresent(v -> used.computeIfAbsent(v, k -> new ArrayList<>()).add(e.name() + "." + d.item().name()));
			}
		}
		return text(o, "all vocabularies", used);
	}

	private static void collect(ResolvedOntology o, Entity entity, Map<String, Object> shape,
			Map<Vocabulary, List<String>> used) {
		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) shape.get("properties");
		for (ResolvedOntology.Declared<Attribute> d : o.attributes(entity)) {
			if (properties.containsKey(d.item().name())) {
				o.vocabulary(d).ifPresent(v -> used.computeIfAbsent(v, k -> new ArrayList<>())
						.add(entity.name() + "." + d.item().name()));
			}
		}
	}

	private static String text(ResolvedOntology o, String scope, Map<Vocabulary, List<String>> used) {
		StringBuilder out = new StringBuilder();
		out.append("Glossary: ").append(o.own().name()).append(" ontology");
		if (o.own().version() != null) {
			out.append(' ').append(o.own().version());
		}
		out.append(", ").append(scope).append(".\n");
		out.append("Use these codes exactly. Match what the text says to the closest definition or synonym.\n");
		for (Map.Entry<Vocabulary, List<String>> entry : used.entrySet()) {
			Vocabulary v = entry.getKey();
			out.append('\n').append(v.name());
			if (v.description() != null) {
				out.append(": ").append(v.description().strip());
			}
			out.append('\n');
			if (!entry.getValue().isEmpty()) {
				out.append("Used for ").append(String.join(", ", entry.getValue())).append(".\n");
			}
			for (Vocabulary.Term t : v.terms()) {
				out.append("- ").append(t.code());
				if (t.definition() != null) {
					String definition = t.definition().strip();
					out.append(": ").append(definition).append(definition.endsWith(".") ? "" : ".");
				}
				if (!t.synonyms().isEmpty()) {
					out.append(" Also said as: ").append(String.join(", ", t.synonyms())).append('.');
				}
				out.append('\n');
			}
		}
		return out.toString();
	}
}

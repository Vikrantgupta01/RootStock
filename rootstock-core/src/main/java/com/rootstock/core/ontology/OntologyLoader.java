package com.rootstock.core.ontology;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads an ontology YAML file into an {@link Ontology}. Strict about shape, so a
 * typo is reported rather than silently ignored: an unknown key, a list where a
 * mapping belongs, a duplicate key. Whether names refer to anything is the
 * {@link OntologyValidator}'s job. Every problem is collected and thrown
 * together in one {@link OntologyException}.
 */
public final class OntologyLoader {

	public static final String API_VERSION = "rootstock/v1";
	public static final String KIND = "Ontology";

	public Ontology load(Path file) {
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new OntologyException(List.of(new OntologyProblem("", "Cannot read " + file + ": " + e.getMessage())));
		}
		return parse(text);
	}

	public Ontology parse(String text) {
		Object root = readYaml(text);
		List<OntologyProblem> problems = new ArrayList<>();
		Node doc = Node.of(root, "", problems);
		if (root == null) {
			throw new OntologyException(List.of(new OntologyProblem("", "The file is empty")));
		}
		doc.allow("apiVersion", "kind", "metadata", "entities", "vocabularies", "constraints", "projections");
		if (!API_VERSION.equals(doc.string("apiVersion"))) {
			problems.add(new OntologyProblem("apiVersion", "expected " + API_VERSION + ", found " + doc.string("apiVersion")));
		}
		if (!KIND.equals(doc.string("kind"))) {
			problems.add(new OntologyProblem("kind", "expected " + KIND + ", found " + doc.string("kind")));
		}
		Node metadata = doc.node("metadata");
		metadata.allow("name", "version", "extends", "description");
		String name = metadata.string("name");
		if (name == null || name.isBlank()) {
			problems.add(new OntologyProblem("metadata.name", "is required"));
			name = "unnamed";
		}

		Map<String, Entity> entities = new LinkedHashMap<>();
		doc.entries("entities").forEach((entityName, body) -> entities.put(entityName,
				entity(entityName, Node.of(body, "entities." + entityName, problems))));
		Map<String, Vocabulary> vocabularies = new LinkedHashMap<>();
		String origin = name;
		doc.entries("vocabularies").forEach((vocabName, body) -> vocabularies.put(vocabName,
				vocabulary(vocabName, origin, Node.of(body, "vocabularies." + vocabName, problems))));
		List<Constraint> constraints = new ArrayList<>();
		List<Object> rawConstraints = doc.list("constraints");
		for (int i = 0; i < rawConstraints.size(); i++) {
			constraints.add(constraint(Node.of(rawConstraints.get(i), "constraints[" + i + "]", problems)));
		}
		Map<String, Projection> projections = new LinkedHashMap<>();
		doc.entries("projections").forEach((projectionName, body) -> projections.put(projectionName,
				projection(projectionName, Node.of(body, "projections." + projectionName, problems))));

		if (!problems.isEmpty()) {
			throw new OntologyException(problems);
		}
		List<Entity> withOrigin = entities.values().stream()
				.map(e -> new Entity(e.name(), origin, e.extendsRef(), e.description(), e.attributes(), e.relations()))
				.toList();
		Map<String, Entity> byName = new LinkedHashMap<>();
		withOrigin.forEach(e -> byName.put(e.name(), e));
		return new Ontology(name, metadata.string("version"), metadata.string("extends"), metadata.string("description"),
				byName, vocabularies, constraints, projections);
	}

	private static Object readYaml(String text) {
		LoaderOptions options = new LoaderOptions();
		options.setAllowDuplicateKeys(false);
		options.setMaxAliasesForCollections(20);
		try {
			return new Yaml(new SafeConstructor(options)).load(text);
		}
		catch (MarkedYAMLException e) {
			Mark mark = e.getProblemMark();
			String what = e.getProblem() == null ? e.getMessage() : e.getProblem();
			if (e.getContext() != null) {
				what = e.getContext() + ": " + what;
			}
			throw new OntologyException(List.of(new OntologyProblem(
					mark == null ? "" : "line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1),
					"not valid YAML: " + what)));
		}
		catch (YAMLException e) {
			throw new OntologyException(List.of(new OntologyProblem("", "not valid YAML: " + e.getMessage())));
		}
	}

	private static Entity entity(String name, Node node) {
		node.allow("extends", "description", "attributes", "relations");
		List<Attribute> attributes = new ArrayList<>();
		node.entries("attributes").forEach((attrName, body) -> {
			Node a = Node.of(body, node.at + ".attributes." + attrName, node.problems);
			a.allow("type", "vocab", "required", "many", "min", "max", "pii", "description");
			attributes.add(new Attribute(attrName, a.string("type"), a.string("vocab"), a.bool("required"),
					a.bool("many"), a.number("min"), a.number("max"), a.bool("pii"), a.string("description")));
		});
		List<Relation> relations = new ArrayList<>();
		node.entries("relations").forEach((relName, body) -> {
			Node r = Node.of(body, node.at + ".relations." + relName, node.problems);
			r.allow("to", "field", "required", "many", "min", "max", "description");
			relations.add(new Relation(relName, r.string("to"), r.string("field"), r.bool("required"), r.bool("many"),
					r.integer("min"), r.integer("max"), r.string("description")));
		});
		return new Entity(name, null, node.string("extends"), node.string("description"), attributes, relations);
	}

	/** Codes are the keys; {@code description} (lower case, so never a code) describes the vocabulary itself. */
	private static Vocabulary vocabulary(String name, String origin, Node node) {
		List<Vocabulary.Term> terms = new ArrayList<>();
		node.raw().forEach((code, body) -> {
			if (code.equals("description")) {
				return;
			}
			Node t = Node.of(body, node.at + "." + code, node.problems);
			t.allow("definition", "synonyms");
			terms.add(new Vocabulary.Term(code, t.string("definition"), t.strings("synonyms")));
		});
		return new Vocabulary(name, origin, node.string("description"), terms);
	}

	private static Constraint constraint(Node node) {
		node.allow("id", "description", "when", "require");
		return new Constraint(node.string("id"), node.string("description"), node.entries("when"),
				node.entries("require"));
	}

	private static Projection projection(String name, Node node) {
		node.allow("root", "embed", "reference", "include", "description");
		return new Projection(name, node.string("root"), node.strings("embed"), node.strings("reference"),
				node.strings("include"), node.string("description"));
	}

	/** A YAML mapping being read, with where it is, for messages. */
	private static final class Node {

		private final Map<String, Object> map;
		final String at;
		final List<OntologyProblem> problems;

		private Node(Map<String, Object> map, String at, List<OntologyProblem> problems) {
			this.map = map;
			this.at = at;
			this.problems = problems;
		}

		/** An absent value reads as an empty mapping: {@code Party:} with nothing under it is fine. */
		static Node of(Object value, String at, List<OntologyProblem> problems) {
			Map<String, Object> map = new LinkedHashMap<>();
			if (value instanceof Map<?, ?> m) {
				m.forEach((k, v) -> map.put(String.valueOf(k), v));
			}
			else if (value != null) {
				problems.add(new OntologyProblem(at, "expected a mapping (key: value lines), found " + describe(value)));
			}
			return new Node(map, at, problems);
		}

		Map<String, Object> raw() {
			return map;
		}

		void allow(String... keys) {
			List<String> allowed = List.of(keys);
			for (String key : map.keySet()) {
				if (!allowed.contains(key)) {
					problems.add(new OntologyProblem(path(key), "unknown key '" + key + "'; expected one of " + allowed));
				}
			}
		}

		String string(String key) {
			Object v = map.get(key);
			if (v == null) {
				return null;
			}
			if (v instanceof Map || v instanceof List) {
				problems.add(new OntologyProblem(path(key), "expected a single value, found " + describe(v)));
				return null;
			}
			return String.valueOf(v);
		}

		boolean bool(String key) {
			Object v = map.get(key);
			if (v == null) {
				return false;
			}
			if (v instanceof Boolean b) {
				return b;
			}
			problems.add(new OntologyProblem(path(key), "expected true or false, found " + describe(v)));
			return false;
		}

		BigDecimal number(String key) {
			Object v = map.get(key);
			if (v == null) {
				return null;
			}
			if (v instanceof Number n) {
				return new BigDecimal(n.toString());
			}
			problems.add(new OntologyProblem(path(key), "expected a number, found " + describe(v)));
			return null;
		}

		Integer integer(String key) {
			Object v = map.get(key);
			if (v == null) {
				return null;
			}
			if (v instanceof Integer i) {
				return i;
			}
			problems.add(new OntologyProblem(path(key), "expected a whole number, found " + describe(v)));
			return null;
		}

		/** A list of single values; one value on its own is read as a list of one. */
		List<String> strings(String key) {
			Object v = map.get(key);
			if (v == null) {
				return List.of();
			}
			List<?> items = v instanceof List<?> l ? l : List.of(v);
			List<String> out = new ArrayList<>();
			for (Object item : items) {
				if (item instanceof Map || item instanceof List || item == null) {
					problems.add(new OntologyProblem(path(key), "expected a list of names, found " + describe(item)));
				}
				else {
					out.add(String.valueOf(item));
				}
			}
			return out;
		}

		List<Object> list(String key) {
			Object v = map.get(key);
			if (v == null) {
				return List.of();
			}
			if (v instanceof List<?> l) {
				return new ArrayList<>(l);
			}
			problems.add(new OntologyProblem(path(key), "expected a list (lines starting with '-'), found " + describe(v)));
			return List.of();
		}

		Node node(String key) {
			return of(map.get(key), path(key), problems);
		}

		Map<String, Object> entries(String key) {
			return node(key).map;
		}

		private String path(String key) {
			return at.isEmpty() ? key : at + "." + key;
		}

		private static String describe(Object v) {
			if (v == null) {
				return "nothing";
			}
			if (v instanceof Map) {
				return "a mapping";
			}
			if (v instanceof List) {
				return "a list";
			}
			return "'" + v + "'";
		}
	}
}

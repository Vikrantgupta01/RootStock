package com.rootstock.core.graph;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.rootstock.core.rules.RuleSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a pack's {@code graph.yaml} and {@code agents/*.yaml}. Each file is
 * checked against its JSON Schema ({@code schemas/graph.schema.json},
 * {@code schemas/agent.schema.json} in rootstock-core) before it is bound, so a
 * typo or a wrong value type is reported with where it is. Every problem in
 * every file is collected and thrown together.
 */
public final class PackGraphLoader {

	public static final String GRAPH_FILE = "graph.yaml";
	public static final String GRAPHS_DIR = "graphs";
	public static final String AGENTS_DIR = "agents";
	public static final String RULES_FILE = "rules.yaml";

	/** rules.yaml as bound: each rule's entry, settings and all. */
	record RulesDefinition(List<Map<String, Object>> rules) {
	}

	private static final JsonMapper JSON = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.build();

	private final Schema graphSchema = schema("/schemas/graph.schema.json");
	private final Schema agentSchema = schema("/schemas/agent.schema.json");
	private final Schema rulesSchema = schema("/schemas/rules.schema.json");

	public static boolean hasGraph(Path packDir) {
		return Files.isRegularFile(packDir.resolve(GRAPH_FILE)) || !yamlFiles(packDir.resolve(GRAPHS_DIR)).isEmpty();
	}

	/** The pack's only graph (e.g. a pack with just graph.yaml). */
	public PackGraph load(String pack, Path packDir) {
		List<PackGraph> graphs = loadAll(pack, packDir);
		if (graphs.size() != 1) {
			throw new IllegalStateException("Pack '" + pack + "' has " + graphs.size() + " graphs; use loadAll");
		}
		return graphs.getFirst();
	}

	/** Every graph of the pack (graph.yaml and graphs/*.yaml), each with the pack's agents and rules. */
	public List<PackGraph> loadAll(String pack, Path packDir) {
		List<GraphProblem> problems = new ArrayList<>();
		Map<String, GraphDefinition> graphs = new LinkedHashMap<>();
		if (Files.isRegularFile(packDir.resolve(GRAPH_FILE))) {
			GraphDefinition g = read(packDir.resolve(GRAPH_FILE), GRAPH_FILE, graphSchema, GraphDefinition.class,
					problems);
			if (g != null) {
				graphs.put(GRAPH_FILE, g);
			}
		}
		for (Path file : yamlFiles(packDir.resolve(GRAPHS_DIR))) {
			String name = GRAPHS_DIR + "/" + file.getFileName();
			GraphDefinition g = read(file, name, graphSchema, GraphDefinition.class, problems);
			if (g == null) {
				continue;
			}
			String expected = file.getFileName().toString().replaceFirst("\\.ya?ml$", "");
			if (!g.metadata().name().equals(expected)) {
				problems.add(new GraphProblem(name, "metadata.name",
						"'" + g.metadata().name() + "' differs from the file name '" + expected + "'"));
			}
			graphs.put(name, g);
		}
		Map<String, AgentDefinition> agents = new LinkedHashMap<>();
		for (Path file : agentFiles(packDir.resolve(AGENTS_DIR))) {
			String name = AGENTS_DIR + "/" + file.getFileName();
			AgentDefinition agent = read(file, name, agentSchema, AgentDefinition.class, problems);
			if (agent == null) {
				continue;
			}
			String expected = file.getFileName().toString().replaceFirst("\\.ya?ml$", "");
			if (!agent.name().equals(expected)) {
				problems.add(new GraphProblem(name, "metadata.name",
						"'" + agent.name() + "' differs from the file name '" + expected + "'"));
			}
			else if (agents.put(agent.name(), agent) != null) {
				problems.add(new GraphProblem(name, "metadata.name", "agent '" + agent.name() + "' is defined twice"));
			}
		}
		List<RuleSpec> rules = List.of();
		if (Files.isRegularFile(packDir.resolve(RULES_FILE))) {
			RulesDefinition definition = read(packDir.resolve(RULES_FILE), RULES_FILE, rulesSchema,
					RulesDefinition.class, problems);
			rules = definition == null ? List.of() : definition.rules().stream().map(PackGraphLoader::spec).toList();
		}
		if (!problems.isEmpty()) {
			throw new GraphDefinitionException("Pack '" + pack + "' has an unreadable graph, agents or rules", problems);
		}
		List<RuleSpec> packRules = rules;
		return graphs.entrySet().stream().map(e -> new PackGraph(pack, e.getValue(), agents, packRules, e.getKey()))
				.toList();
	}

	private <T> T read(Path file, String name, Schema schema, Class<T> type, List<GraphProblem> problems) {
		Object yaml;
		try {
			yaml = yaml(Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			problems.add(new GraphProblem(name, "", "cannot be read: " + e.getMessage()));
			return null;
		}
		catch (MarkedYAMLException e) {
			Mark mark = e.getProblemMark();
			problems.add(new GraphProblem(name, mark == null ? "" : "line " + (mark.getLine() + 1),
					"not valid YAML: " + (e.getProblem() == null ? e.getMessage() : e.getProblem())));
			return null;
		}
		catch (YAMLException e) {
			problems.add(new GraphProblem(name, "", "not valid YAML: " + e.getMessage()));
			return null;
		}
		if (yaml == null) {
			problems.add(new GraphProblem(name, "", "the file is empty"));
			return null;
		}
		JsonNode node = JSON.valueToTree(yaml);
		List<Error> errors = schema.validate(node);
		if (!errors.isEmpty()) {
			errors.forEach(e -> problems.add(new GraphProblem(name, location(e), e.getMessage())));
			return null;
		}
		JsonNode bindable = node;
		if (node.isObject() && node.has("apiVersion")) {
			// apiVersion and kind are checked by the schema; the records don't carry them.
			bindable = ((tools.jackson.databind.node.ObjectNode) node.deepCopy()).remove(List.of("apiVersion", "kind"));
		}
		return JSON.treeToValue(bindable, type);
	}

	private static final List<String> RULE_KEYS = List.of("id", "kind", "severity", "answerableBy", "description",
			"message");

	/** A rule's own keys; the rest are its kind's settings. */
	private static RuleSpec spec(Map<String, Object> entry) {
		Map<String, Object> config = new LinkedHashMap<>(entry);
		RULE_KEYS.forEach(config::remove);
		return new RuleSpec(text(entry.get("id")), text(entry.get("kind")), text(entry.get("severity")),
				text(entry.get("answerableBy")), text(entry.get("description")), text(entry.get("message")), config);
	}

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	/** The validator's JSON pointer ({@code /nodes/3/type}) as people write it ({@code nodes[3].type}). */
	public static String location(Error e) {
		String path = e.getInstanceLocation() == null ? "" : e.getInstanceLocation().toString();
		StringBuilder out = new StringBuilder();
		for (String part : path.replaceFirst("^\\$\\.?", "").split("[/.]")) {
			if (part.isEmpty()) {
				continue;
			}
			if (part.matches("\\d+")) {
				out.append('[').append(part).append(']');
			}
			else {
				out.append(out.isEmpty() ? "" : ".").append(part.replace("~1", "/").replace("~0", "~"));
			}
		}
		return out.toString();
	}

	private static Object yaml(String text) {
		LoaderOptions options = new LoaderOptions();
		options.setAllowDuplicateKeys(false);
		options.setMaxAliasesForCollections(20);
		return new Yaml(new SafeConstructor(options)).load(text);
	}

	private static List<Path> yamlFiles(Path dir) {
		return agentFiles(dir);
	}

	private static List<Path> agentFiles(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().matches(".*\\.ya?ml"))
					.sorted().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Schema schema(String resource) {
		try (InputStream in = PackGraphLoader.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Missing " + resource);
			}
			return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
					.getSchema(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

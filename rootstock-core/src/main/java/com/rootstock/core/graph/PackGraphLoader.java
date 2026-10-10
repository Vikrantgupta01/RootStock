package com.rootstock.core.graph;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
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
	public static final String AGENTS_DIR = "agents";

	private static final JsonMapper JSON = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
			.build();

	private final Schema graphSchema = schema("/schemas/graph.schema.json");
	private final Schema agentSchema = schema("/schemas/agent.schema.json");

	public static boolean hasGraph(Path packDir) {
		return Files.isRegularFile(packDir.resolve(GRAPH_FILE));
	}

	public PackGraph load(String pack, Path packDir) {
		List<GraphProblem> problems = new ArrayList<>();
		GraphDefinition graph = read(packDir.resolve(GRAPH_FILE), GRAPH_FILE, graphSchema, GraphDefinition.class,
				problems);
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
		if (!problems.isEmpty()) {
			throw new GraphDefinitionException("Pack '" + pack + "' has an unreadable graph or agents", problems);
		}
		return new PackGraph(pack, graph, agents);
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
		if (type == AgentDefinition.class || type == GraphDefinition.class) {
			// apiVersion and kind are checked by the schema; the records don't carry them.
			bindable = ((tools.jackson.databind.node.ObjectNode) node.deepCopy()).remove(List.of("apiVersion", "kind"));
		}
		return JSON.treeToValue(bindable, type);
	}

	/** The validator's JSON pointer ({@code /nodes/3/type}) as people write it ({@code nodes[3].type}). */
	static String location(Error e) {
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

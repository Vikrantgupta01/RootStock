package com.rootstock.core.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
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

/**
 * The copies of a pack's prompts kept in its {@code prompts/} folder, used when
 * Langfuse cannot be reached or does not have the prompt yet. Each file has the
 * shape of Langfuse's create-prompt request, so the same file is what gets
 * published:
 *
 * <pre>
 * name: acme/extract-case
 * type: chat
 * labels: [production]
 * prompt:
 *   - { role: system, content: ... }
 *   - { role: user, content: ... }
 * </pre>
 */
public final class BundledPrompts {

	public static final String DIR = "prompts";

	private BundledPrompts() {
	}

	/** Every prompt under {@code <pack>/prompts/}, by name; empty when there is no such folder. */
	public static Map<String, PromptTemplate> load(Path packDir) {
		Path dir = packDir.resolve(DIR);
		Map<String, PromptTemplate> prompts = new LinkedHashMap<>();
		if (!Files.isDirectory(dir)) {
			return prompts;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml"))
					.sorted().toList()) {
				PromptTemplate t = parse(Files.readString(file), file);
				if (prompts.put(t.name(), t) != null) {
					throw new IllegalArgumentException(file + ": a second prompt called '" + t.name() + "'");
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return prompts;
	}

	static PromptTemplate parse(String yaml, Path file) {
		Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
		if (!(root instanceof Map<?, ?> map) || !(map.get("name") instanceof String name)) {
			throw new IllegalArgumentException(file + ": a prompt file needs a name");
		}
		Object prompt = map.get("prompt");
		List<PromptTemplate.Part> parts = new ArrayList<>();
		if (prompt instanceof List<?> messages) {
			for (Object m : messages) {
				if (!(m instanceof Map<?, ?> message) || !(message.get("role") instanceof String role)
						|| !(message.get("content") instanceof String content)) {
					throw new IllegalArgumentException(file + ": each message needs a role and content");
				}
				parts.add(new PromptTemplate.Part(role, content));
			}
		}
		else if (prompt instanceof String text) {
			parts.add(new PromptTemplate.Part("user", text));
		}
		else {
			throw new IllegalArgumentException(file + ": 'prompt' must be text or a list of messages");
		}
		return new PromptTemplate(name, null, null, PromptTemplate.Source.BUNDLED, parts);
	}
}

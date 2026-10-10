package com.rootstock.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prompt as kept in Langfuse: a name, the version served for a label, and its
 * messages with {@code {{variable}}} placeholders.
 *
 * @param version  Langfuse's version number; null for a bundled copy
 * @param source   where it came from, so traces and screens can say
 * @param messages a Langfuse text prompt is one user message
 */
public record PromptTemplate(String name, String label, Integer version, Source source, List<Part> messages) {

	public enum Source {
		LANGFUSE, BUNDLED
	}

	/** @param role {@code system}, {@code user} or {@code assistant} */
	public record Part(String role, String content) {
	}

	private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

	public PromptTemplate {
		messages = List.copyOf(messages);
	}

	/** "acme/extract-case v3 (langfuse)", for audit notes and logs. */
	public String describe() {
		return name + (version == null ? "" : " v" + version) + " (" + source.name().toLowerCase() + ")";
	}

	/** The placeholders the messages use. */
	public Set<String> variables() {
		Set<String> names = new TreeSet<>();
		for (Part p : messages) {
			Matcher m = VARIABLE.matcher(p.content());
			while (m.find()) {
				names.add(m.group(1));
			}
		}
		return names;
	}

	/**
	 * The messages with every placeholder filled in.
	 *
	 * @throws IllegalArgumentException if a placeholder has no value, rather than sending the model a literal {@code {{x}}}
	 */
	public List<Part> render(Map<String, String> values) {
		Set<String> missing = new TreeSet<>(variables());
		missing.removeAll(values.keySet());
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Prompt " + describe() + " needs " + missing + "; given " + values.keySet());
		}
		List<Part> out = new ArrayList<>();
		for (Part p : messages) {
			out.add(new Part(p.role(), VARIABLE.matcher(p.content())
					.replaceAll(m -> Matcher.quoteReplacement(values.get(m.group(1))))));
		}
		return out;
	}
}

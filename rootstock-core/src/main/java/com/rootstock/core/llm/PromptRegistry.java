package com.rootstock.core.llm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompts by name and label. Langfuse is the source of truth, so a prompt can
 * change without a release; each one is cached for a few minutes. When
 * Langfuse cannot be reached the last copy fetched is used, and failing that
 * the copy bundled with the pack. A prompt Langfuse does not have (yet) also
 * falls back to the bundled copy, with a warning.
 */
public final class PromptRegistry {

	private static final Logger log = LoggerFactory.getLogger(PromptRegistry.class);

	private record Cached(PromptTemplate prompt, Instant at) {
	}

	private final PromptSource remote;
	private final Duration ttl;
	private final Clock clock;
	private final Map<String, PromptTemplate> bundled = new ConcurrentHashMap<>();
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();
	private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

	/**
	 * @param remote Langfuse; null when it is not configured, so only bundled copies are used
	 * @param ttl    how long a fetched prompt is used before asking again
	 */
	public PromptRegistry(PromptSource remote, Duration ttl, Clock clock) {
		this.remote = remote;
		this.ttl = ttl;
		this.clock = clock;
	}

	/** Adds a pack's bundled copies; a name already added is an error (two packs, one prompt). */
	public void addBundled(Map<String, PromptTemplate> prompts) {
		prompts.forEach((name, prompt) -> {
			if (bundled.putIfAbsent(name, prompt) != null) {
				throw new IllegalArgumentException("Two packs bundle a prompt called '" + name + "'");
			}
		});
	}

	public boolean hasBundled(String name) {
		return bundled.containsKey(name);
	}

	/**
	 * @param label e.g. {@code production}; null means {@code production}
	 * @throws PromptNotFoundException when neither Langfuse nor any pack has it
	 */
	public PromptTemplate get(String name, String label) {
		String l = label == null ? "production" : label;
		String key = name + "@" + l;
		Cached cached = cache.get(key);
		if (cached != null && cached.at().plus(ttl).isAfter(clock.instant())) {
			return cached.prompt();
		}
		if (remote != null) {
			try {
				Optional<PromptTemplate> fetched = remote.fetch(name, l);
				if (fetched.isPresent()) {
					cache.put(key, new Cached(fetched.get(), clock.instant()));
					return fetched.get();
				}
				warnOnce(key, "Langfuse has no prompt '{}' labelled '{}'; using the copy bundled with the pack", name, l);
			}
			catch (RuntimeException e) {
				if (cached != null && cached.prompt().source() == PromptTemplate.Source.LANGFUSE) {
					log.warn("Langfuse unreachable ({}); using the last copy of '{}' ({})", e.getMessage(), name,
							cached.prompt().describe());
					cache.put(key, new Cached(cached.prompt(), clock.instant()));
					return cached.prompt();
				}
				warnOnce(key, "Langfuse unreachable ({}); using the bundled copy of '{}'", e.getMessage(), name);
			}
		}
		PromptTemplate copy = bundled.get(name);
		if (copy == null) {
			throw new PromptNotFoundException("No prompt '" + name + "' (label '" + l + "') in Langfuse"
					+ (remote == null ? " (not configured)" : "") + " or bundled in a pack's " + BundledPrompts.DIR
					+ "/ folder");
		}
		PromptTemplate fallback = new PromptTemplate(copy.name(), l, null, PromptTemplate.Source.BUNDLED,
				copy.messages());
		// Kept for the same time, so an unreachable Langfuse costs one timeout per ttl, not one per case.
		cache.put(key, new Cached(fallback, clock.instant()));
		return fallback;
	}

	private void warnOnce(String key, String message, Object... args) {
		if (warned.putIfAbsent(key, Boolean.TRUE) == null) {
			log.warn(message, args);
		}
	}
}

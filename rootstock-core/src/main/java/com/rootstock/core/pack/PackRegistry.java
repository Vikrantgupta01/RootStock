package com.rootstock.core.pack;

import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.ResolvedOntology;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The domain packs Rootstock has loaded, and the core ontology they extend.
 * {@link #reload()} re-reads every pack from disk and swaps the result in whole,
 * so readers never see a half-loaded set.
 */
public final class PackRegistry {

	/** Everything loaded at one moment. */
	public record Snapshot(List<LoadedPack> packs, List<String> pathProblems, Instant loadedAt) {
	}

	private final Ontology core;
	private final List<Path> paths;
	private final PackLoader loader;
	private volatile Snapshot snapshot;

	public PackRegistry(Ontology core, List<Path> paths) {
		this.core = core;
		this.paths = List.copyOf(paths);
		this.loader = new PackLoader(core);
		reload();
	}

	public Snapshot reload() {
		PackLoader.Result result = loader.load(paths);
		snapshot = new Snapshot(result.packs(), result.pathProblems(), Instant.now());
		return snapshot;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	public Ontology core() {
		return core;
	}

	public List<Path> paths() {
		return paths;
	}

	public Optional<LoadedPack> pack(String name) {
		return snapshot.packs().stream().filter(p -> p.name().equals(name)).findFirst();
	}

	/** The pack's ontology resolved against the core, when it is valid. */
	public Optional<ResolvedOntology> ontology(String packName) {
		return pack(packName).filter(p -> p.status() == LoadedPack.Status.VALID)
				.map(p -> new ResolvedOntology(core, p.ontology()));
	}
}

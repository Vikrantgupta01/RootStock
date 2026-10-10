package com.rootstock.core.pack;

import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyException;
import com.rootstock.core.ontology.OntologyLoader;
import com.rootstock.core.ontology.OntologyProblem;
import com.rootstock.core.ontology.OntologyValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds domain packs in the configured folders and loads each one's ontology.
 * A path may name one pack (a folder holding pack YAML) or a folder of packs.
 * A pack whose ontology is broken is still returned, marked INVALID with every
 * problem found, so it can be shown and fixed without stopping Rootstock.
 */
public final class PackLoader {

	private final Ontology core;
	private final OntologyLoader loader = new OntologyLoader();
	private final OntologyValidator validator = new OntologyValidator();

	public PackLoader(Ontology core) {
		this.core = core;
	}

	public record Result(List<LoadedPack> packs, List<String> pathProblems) {
	}

	public Result load(List<Path> paths) {
		List<LoadedPack> packs = new ArrayList<>();
		List<String> pathProblems = new ArrayList<>();
		for (Path path : paths) {
			Path dir = path.toAbsolutePath().normalize();
			if (!Files.isDirectory(dir)) {
				pathProblems.add("Pack path " + dir + " is not a folder");
				continue;
			}
			if (isPack(dir)) {
				packs.add(loadPack(dir));
				continue;
			}
			List<Path> children = subfolders(dir);
			List<Path> found = children.stream().filter(PackLoader::isPack).toList();
			if (found.isEmpty()) {
				pathProblems.add("No packs in " + dir + " (a pack is a folder with YAML files such as "
						+ LoadedPack.ONTOLOGY_FILE + ")");
			}
			found.forEach(child -> packs.add(loadPack(child)));
		}
		List<String> seen = new ArrayList<>();
		for (LoadedPack pack : packs) {
			if (seen.contains(pack.name())) {
				pathProblems.add("Two packs are called '" + pack.name() + "'; names must be unique");
			}
			seen.add(pack.name());
		}
		return new Result(packs, pathProblems);
	}

	LoadedPack loadPack(Path dir) {
		String name = dir.getFileName().toString();
		List<String> files = yamlFiles(dir);
		Path ontologyFile = dir.resolve(LoadedPack.ONTOLOGY_FILE);
		if (!Files.isRegularFile(ontologyFile)) {
			return new LoadedPack(name, dir.toString(), files, LoadedPack.Status.NO_ONTOLOGY, null, List.of());
		}
		Ontology ontology;
		try {
			ontology = loader.load(ontologyFile);
		}
		catch (OntologyException e) {
			return new LoadedPack(name, dir.toString(), files, LoadedPack.Status.INVALID, null, e.problems());
		}
		List<OntologyProblem> problems = new ArrayList<>(validator.validate(ontology, core));
		if (!ontology.name().equals(name)) {
			problems.add(0, new OntologyProblem("metadata.name", "'" + ontology.name()
					+ "' differs from the pack's folder name '" + name + "'"));
		}
		return problems.isEmpty()
				? new LoadedPack(name, dir.toString(), files, LoadedPack.Status.VALID, ontology, List.of())
				: new LoadedPack(name, dir.toString(), files, LoadedPack.Status.INVALID, null, problems);
	}

	private static boolean isPack(Path dir) {
		return !yamlFiles(dir).isEmpty();
	}

	private static List<String> yamlFiles(Path dir) {
		try (Stream<Path> list = Files.list(dir)) {
			return list.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".yaml") || n.endsWith(".yml")).sorted().toList();
		}
		catch (IOException e) {
			return List.of();
		}
	}

	private static List<Path> subfolders(Path dir) {
		try (Stream<Path> list = Files.list(dir)) {
			return list.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList();
		}
		catch (IOException e) {
			return List.of();
		}
	}
}

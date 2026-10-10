package com.rootstock.core.pack;

import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyProblem;
import java.util.List;

/**
 * A domain pack found on disk, and what became of its ontology.
 *
 * @param name     the pack's folder name
 * @param location the folder, as an absolute path
 * @param files    the YAML files it holds, by name
 * @param ontology the loaded ontology when {@code status} is VALID; otherwise null
 * @param problems why the ontology is INVALID; empty otherwise
 */
public record LoadedPack(String name, String location, List<String> files, Status status, Ontology ontology,
		List<OntologyProblem> problems) {

	public static final String ONTOLOGY_FILE = "ontology.yaml";

	public enum Status {
		/** The ontology loaded and passed validation. */
		VALID,
		/** The ontology could not be read or does not make sense; see the problems. */
		INVALID,
		/** The pack has no ontology.yaml. */
		NO_ONTOLOGY
	}

	public LoadedPack {
		files = List.copyOf(files);
		problems = List.copyOf(problems);
	}
}

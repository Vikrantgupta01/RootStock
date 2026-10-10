package com.rootstock.core.ontology;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class OntologyFixtures {

	private OntologyFixtures() {
	}

	static String resource(String path) {
		try (InputStream in = OntologyFixtures.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalArgumentException("No resource " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static Ontology core() {
		return new OntologyLoader().parse(resource("/ontology/rootstock-core.yaml"));
	}

	static String sampleText() {
		return resource("/ontology/sample-pack.yaml");
	}

	static ResolvedOntology sample() {
		return new ResolvedOntology(core(), new OntologyLoader().parse(sampleText()));
	}
}

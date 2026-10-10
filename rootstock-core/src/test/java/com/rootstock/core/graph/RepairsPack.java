package com.rootstock.core.graph;

import com.rootstock.core.graph.stub.StubNodes;
import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyLoader;
import com.rootstock.core.ontology.ResolvedOntology;
import com.rootstock.core.tools.ToolAccess;
import com.rootstock.core.tools.ToolCatalog;
import com.rootstock.core.tools.ToolDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** The made-up repairs pack in test resources: graph, agents, ontology and a tool catalog to match. */
public final class RepairsPack {

	private RepairsPack() {
	}

	public static Path dir() {
		try {
			return Path.of(RepairsPack.class.getResource("/packs/repairs").toURI());
		}
		catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	public static PackGraph load() {
		return new PackGraphLoader().load("repairs", dir());
	}

	/** A copy of the pack in {@code target}, with {@code file} changed by {@code edit}. */
	public static Path copyWith(Path target, String file, UnaryOperator<String> edit) {
		try (Stream<Path> all = Files.walk(dir())) {
			for (Path p : all.sorted(Comparator.naturalOrder()).toList()) {
				Path to = target.resolve(dir().relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(to);
				}
				else {
					Files.copy(p, to);
				}
			}
			Path changed = target.resolve(file);
			Files.writeString(changed, edit.apply(Files.readString(changed)));
			return target;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static ResolvedOntology ontology() {
		try {
			OntologyLoader loader = new OntologyLoader();
			Ontology core = loader.parse(new String(RepairsPack.class.getResourceAsStream("/ontology/rootstock-core.yaml")
					.readAllBytes()));
			return new ResolvedOntology(core, loader.load(dir().resolve("ontology.yaml")));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static ToolCatalog tools() {
		return new ToolCatalog(Map.of(
				"find_property", new ToolDefinition("find_property", "landlord", "find_property", ToolAccess.READ),
				"list_contractors", new ToolDefinition("list_contractors", "landlord", "list_contractors", ToolAccess.READ),
				"book_visit", new ToolDefinition("book_visit", "landlord", "book_visit", ToolAccess.WRITE)),
				Map.of("enrich", List.of("find_property", "list_contractors"), "commit", List.of("book_visit")),
				Set.of("commit"));
	}

	public static NodeRegistry registry() {
		return new NodeRegistry(StubNodes.all(Duration.ZERO));
	}

	public static GraphValidator.Context context() {
		return new GraphValidator.Context(registry(), Set.of("by-priority"), tools(), ontology(), null);
	}
}

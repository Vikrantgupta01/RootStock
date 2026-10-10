package com.rootstock.runtime.ontology;

import com.rootstock.core.ontology.GlossaryRenderer;
import com.rootstock.core.ontology.JsonSchemaGenerator;
import com.rootstock.core.ontology.ResolvedOntology;
import com.rootstock.core.pack.LoadedPack;
import com.rootstock.core.pack.PackRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Ontology explorer: the core ontology, the domain packs found, each pack's
 * concepts and vocabularies, and the schema and glossary generated for each
 * projection. Readable by any signed-in user (definitions, no records);
 * reloading packs from disk is for admins.
 */
@RestController
@RequestMapping("/api/ontology")
public class OntologyController {

	private static final Logger log = LoggerFactory.getLogger(OntologyController.class);

	private final PackRegistry registry;
	private final JsonSchemaGenerator schemas = new JsonSchemaGenerator();
	private final GlossaryRenderer glossaries = new GlossaryRenderer();

	public OntologyController(PackRegistry registry) {
		this.registry = registry;
	}

	@GetMapping
	OntologyViews.Overview overview() {
		return OntologyViews.overview(registry);
	}

	@GetMapping("/core")
	OntologyViews.OntologyView core() {
		return OntologyViews.view(new ResolvedOntology(registry.core(), registry.core()));
	}

	@GetMapping("/packs/{name}")
	OntologyViews.PackDetail pack(@PathVariable String name) {
		LoadedPack pack = registry.pack(name).orElseThrow(() -> notFound("No pack '" + name + "'"));
		return new OntologyViews.PackDetail(OntologyViews.packSummary(pack),
				registry.ontology(name).map(OntologyViews::view).orElse(null));
	}

	/**
	 * A projection's schema and glossary. {@code mode=extraction} gives the schema
	 * a model fills in (any field may be null, no ids), which is also the shape a
	 * reviewer edits.
	 */
	@GetMapping("/packs/{name}/projections/{projection}")
	OntologyViews.ProjectionOutput projection(@PathVariable String name, @PathVariable String projection,
			@RequestParam(defaultValue = "strict") String mode) {
		ResolvedOntology ontology = registry.ontology(name)
				.orElseThrow(() -> notFound("No valid ontology in pack '" + name + "'"));
		if (!ontology.own().projections().containsKey(projection)) {
			throw notFound("No projection '" + projection + "' in pack '" + name + "'");
		}
		JsonSchemaGenerator.Mode schemaMode = switch (mode) {
			case "strict" -> JsonSchemaGenerator.Mode.STRICT;
			case "extraction" -> JsonSchemaGenerator.Mode.EXTRACTION;
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode is strict or extraction");
		};
		return new OntologyViews.ProjectionOutput(name, projection, schemas.generate(ontology, projection, schemaMode),
				glossaries.render(ontology, projection));
	}

	/** Re-reads every pack from disk, e.g. after editing an ontology.yaml. Nothing outside the packs changes. */
	@PostMapping("/reload")
	@PreAuthorize("hasRole('ADMIN')")
	OntologyViews.Overview reload() {
		PackRegistry.Snapshot snapshot = registry.reload();
		snapshot.packs().forEach(p -> log.info("Reloaded pack '{}': {}{}", p.name(), p.status(),
				p.problems().isEmpty() ? "" : " " + p.problems()));
		return OntologyViews.overview(registry);
	}

	private static ResponseStatusException notFound(String message) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
	}
}

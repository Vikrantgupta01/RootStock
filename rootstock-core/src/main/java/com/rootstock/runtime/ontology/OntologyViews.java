package com.rootstock.runtime.ontology;

import com.rootstock.core.ontology.Attribute;
import com.rootstock.core.ontology.Constraint;
import com.rootstock.core.ontology.Entity;
import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyProblem;
import com.rootstock.core.ontology.Projection;
import com.rootstock.core.ontology.Relation;
import com.rootstock.core.ontology.ResolvedOntology;
import com.rootstock.core.ontology.Vocabulary;
import com.rootstock.core.pack.LoadedPack;
import com.rootstock.core.pack.PackRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What the Ontology explorer shows: the model flattened for the screen, with inherited fields marked. */
final class OntologyViews {

	private OntologyViews() {
	}

	record Overview(List<String> paths, List<String> pathProblems, Instant loadedAt, Summary core, List<PackSummary> packs) {
	}

	record Summary(String name, String version, String description, int concepts, int vocabularies, int projections) {
	}

	record PackSummary(String name, String location, List<String> files, LoadedPack.Status status, Summary ontology,
			List<OntologyProblem> problems) {
	}

	record PackDetail(PackSummary pack, OntologyView ontology) {
	}

	record OntologyView(String name, String version, String extendsName, String description, List<EntityView> entities,
			List<Vocabulary> vocabularies, List<Constraint> constraints, List<Projection> projections) {
	}

	/**
	 * @param ancestors the concepts it extends, nearest first, as named from this ontology
	 */
	record EntityView(String name, String extendsRef, List<String> ancestors, String description,
			List<AttributeView> attributes, List<RelationView> relations) {
	}

	/** @param declaredBy the concept that declares it: this one, or one it extends */
	record AttributeView(String name, String type, String vocab, boolean required, boolean many, BigDecimal min,
			BigDecimal max, boolean pii, String description, String declaredBy) {
	}

	record RelationView(String name, String to, String field, boolean required, boolean many, Integer min, Integer max,
			String description, String declaredBy) {
	}

	record ProjectionOutput(String pack, String projection, Map<String, Object> schema, String glossary) {
	}

	static Overview overview(PackRegistry registry) {
		PackRegistry.Snapshot snapshot = registry.snapshot();
		return new Overview(registry.paths().stream().map(p -> p.toAbsolutePath().normalize().toString()).toList(),
				snapshot.pathProblems(), snapshot.loadedAt(), summary(registry.core()),
				snapshot.packs().stream().map(OntologyViews::packSummary).toList());
	}

	static PackSummary packSummary(LoadedPack pack) {
		return new PackSummary(pack.name(), pack.location(), pack.files(), pack.status(),
				pack.ontology() == null ? null : summary(pack.ontology()), pack.problems());
	}

	static Summary summary(Ontology o) {
		return new Summary(o.name(), o.version(), o.description(), o.entities().size(), o.vocabularies().size(),
				o.projections().size());
	}

	static OntologyView view(ResolvedOntology o) {
		Ontology own = o.own();
		List<EntityView> entities = own.entities().values().stream().map(e -> entity(o, e)).toList();
		return new OntologyView(own.name(), own.version(), own.extendsName(), own.description(), entities,
				List.copyOf(own.vocabularies().values()), own.constraints(), List.copyOf(own.projections().values()));
	}

	private static EntityView entity(ResolvedOntology o, Entity e) {
		List<AttributeView> attributes = o.attributes(e).stream().map(d -> {
			Attribute a = d.item();
			return new AttributeView(a.name(), a.type(), a.vocab(), a.required(), a.many(), a.min(), a.max(), a.pii(),
					a.description(), o.qualifiedName(d.declaredBy()));
		}).toList();
		List<RelationView> relations = o.relations(e).stream().map(d -> {
			Relation r = d.item();
			String to = o.target(d).map(o::qualifiedName).orElse(r.to());
			return new RelationView(r.name(), to, r.fieldOrName(), r.required(), r.many(), r.min(), r.max(),
					r.description(), o.qualifiedName(d.declaredBy()));
		}).toList();
		return new EntityView(e.name(), e.extendsRef(), o.ancestors(e).stream().map(o::qualifiedName).toList(),
				e.description(), attributes, relations);
	}
}

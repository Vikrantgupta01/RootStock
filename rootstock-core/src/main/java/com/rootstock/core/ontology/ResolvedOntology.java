package com.rootstock.core.ontology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An ontology seen together with the core ontology it extends, so names can be
 * followed: {@code core.Case} means the core's Case, a plain name means one in
 * the same ontology as the entity that uses it. Lookups never throw; an unknown
 * name is empty, and the {@link OntologyValidator} reports it.
 */
public final class ResolvedOntology {

	public static final String CORE_PREFIX = "core.";

	private final Ontology core;
	private final Ontology own;

	/**
	 * @param core the core ontology
	 * @param own  the ontology to look at; may be the core itself
	 */
	public ResolvedOntology(Ontology core, Ontology own) {
		this.core = core;
		this.own = own;
	}

	public Ontology core() {
		return core;
	}

	public Ontology own() {
		return own;
	}

	public boolean isCore() {
		return own == core;
	}

	/** An entity named from within this ontology. */
	public Optional<Entity> entity(String ref) {
		return entity(ref, own.name());
	}

	/** An entity named from within the ontology called {@code fromOrigin}. */
	public Optional<Entity> entity(String ref, String fromOrigin) {
		if (ref == null) {
			return Optional.empty();
		}
		if (ref.startsWith(CORE_PREFIX)) {
			return Optional.ofNullable(core.entities().get(ref.substring(CORE_PREFIX.length())));
		}
		return Optional.ofNullable(ontologyNamed(fromOrigin).entities().get(ref));
	}

	public Optional<Vocabulary> vocabulary(String ref, String fromOrigin) {
		if (ref == null) {
			return Optional.empty();
		}
		if (ref.startsWith(CORE_PREFIX)) {
			return Optional.ofNullable(core.vocabularies().get(ref.substring(CORE_PREFIX.length())));
		}
		return Optional.ofNullable(ontologyNamed(fromOrigin).vocabularies().get(ref));
	}

	/** How to name this entity from within this ontology: {@code core.Case} for a core one, else its name. */
	public String qualifiedName(Entity entity) {
		return !isCore() && core.name().equals(entity.origin()) ? CORE_PREFIX + entity.name() : entity.name();
	}

	/** The chain of entities this one extends, nearest first. Stops at an unknown name or a cycle. */
	public List<Entity> ancestors(Entity entity) {
		List<Entity> chain = new ArrayList<>();
		Set<Entity> seen = new HashSet<>(List.of(entity));
		Entity current = entity;
		while (current.extendsRef() != null) {
			Optional<Entity> parent = entity(current.extendsRef(), current.origin());
			if (parent.isEmpty() || !seen.add(parent.get())) {
				break;
			}
			chain.add(parent.get());
			current = parent.get();
		}
		return chain;
	}

	/** Something an entity has, and the entity that declared it (itself, or one it extends). */
	public record Declared<T>(T item, Entity declaredBy) {
	}

	/** Every attribute, inherited ones first (from the most general concept down). */
	public List<Declared<Attribute>> attributes(Entity entity) {
		List<Declared<Attribute>> out = new ArrayList<>();
		for (Entity e : lineage(entity)) {
			e.attributes().forEach(a -> out.add(new Declared<>(a, e)));
		}
		return out;
	}

	/** Every relation, inherited ones first. */
	public List<Declared<Relation>> relations(Entity entity) {
		List<Declared<Relation>> out = new ArrayList<>();
		for (Entity e : lineage(entity)) {
			e.relations().forEach(r -> out.add(new Declared<>(r, e)));
		}
		return out;
	}

	public Optional<Declared<Attribute>> attribute(Entity entity, String name) {
		return attributes(entity).stream().filter(d -> d.item().name().equals(name)).findFirst();
	}

	/** A relation by its name or its field name. */
	public Optional<Declared<Relation>> relation(Entity entity, String name) {
		return relations(entity).stream()
				.filter(d -> d.item().name().equals(name) || d.item().fieldOrName().equals(name))
				.findFirst();
	}

	/** The entity a relation points at, resolved from where the relation is declared. */
	public Optional<Entity> target(Declared<Relation> relation) {
		return entity(relation.item().to(), relation.declaredBy().origin());
	}

	public Optional<Vocabulary> vocabulary(Declared<Attribute> attribute) {
		return vocabulary(attribute.item().vocab(), attribute.declaredBy().origin());
	}

	private List<Entity> lineage(Entity entity) {
		Deque<Entity> lineage = new ArrayDeque<>();
		lineage.push(entity);
		ancestors(entity).forEach(lineage::push);
		return new ArrayList<>(lineage);
	}

	private Ontology ontologyNamed(String name) {
		return core.name().equals(name) ? core : own;
	}
}

package com.rootstock.core.ontology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks that an ontology makes sense: every name it uses refers to something,
 * vocabulary values are real codes, constraints and projections point at real
 * fields. Returns every problem found, each with where it is, so a broken pack
 * can be listed with its errors instead of stopping Rootstock.
 */
public final class OntologyValidator {

	private static final Pattern ENTITY_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");
	private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");
	private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]*");

	/** Validates the core ontology on its own. */
	public List<OntologyProblem> validateCore(Ontology core) {
		List<OntologyProblem> problems = new ArrayList<>();
		if (core.extendsName() != null) {
			problems.add(new OntologyProblem("metadata.extends", "the core ontology extends nothing"));
		}
		check(new ResolvedOntology(core, core), problems);
		return problems;
	}

	/** Validates a domain ontology against the core it must extend. */
	public List<OntologyProblem> validate(Ontology ontology, Ontology core) {
		List<OntologyProblem> problems = new ArrayList<>();
		if (ontology.name().equals(core.name())) {
			problems.add(new OntologyProblem("metadata.name", "'" + core.name() + "' is the core ontology's name"));
		}
		if (!core.name().equals(ontology.extendsName())) {
			problems.add(new OntologyProblem("metadata.extends", "must be " + core.name() + ", found "
					+ (ontology.extendsName() == null ? "nothing" : "'" + ontology.extendsName() + "'")));
		}
		check(new ResolvedOntology(core, ontology), problems);
		return problems;
	}

	private void check(ResolvedOntology o, List<OntologyProblem> problems) {
		Ontology own = o.own();
		own.entities().values().forEach(e -> entity(o, e, problems));
		own.vocabularies().values().forEach(v -> vocabulary(v, problems));
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < own.constraints().size(); i++) {
			constraint(o, own.constraints().get(i), "constraints[" + i + "]", ids, problems);
		}
		own.projections().values().forEach(p -> projection(o, p, problems));
	}

	private void entity(ResolvedOntology o, Entity e, List<OntologyProblem> problems) {
		String at = "entities." + e.name();
		if (!ENTITY_NAME.matcher(e.name()).matches()) {
			problems.add(new OntologyProblem(at, "entity names start with a capital letter and use only letters and digits"));
		}
		if (e.extendsRef() != null) {
			Optional<Entity> parent = o.entity(e.extendsRef(), e.origin());
			if (parent.isEmpty()) {
				problems.add(new OntologyProblem(at + ".extends", "unknown concept '" + e.extendsRef() + "'; "
						+ (o.isCore() ? "known: " + o.own().entities().keySet()
								: "core concepts are " + prefixed(o.core().entities().keySet()))));
			}
			else if (cycles(o, e)) {
				problems.add(new OntologyProblem(at + ".extends", "'" + e.name() + "' ends up extending itself"));
			}
		}

		// Field names must be unique across the entity and everything it inherits.
		Map<String, String> fieldOwners = new HashMap<>();
		for (ResolvedOntology.Declared<Attribute> d : o.attributes(e)) {
			claim(fieldOwners, d.item().name(), d.declaredBy(), e, at, problems);
		}
		for (ResolvedOntology.Declared<Relation> d : o.relations(e)) {
			claim(fieldOwners, d.item().fieldOrName(), d.declaredBy(), e, at, problems);
		}

		for (Attribute a : e.attributes()) {
			attribute(o, e, a, at + ".attributes." + a.name(), problems);
		}
		for (Relation r : e.relations()) {
			relation(o, e, r, at + ".relations." + r.name(), problems);
		}
	}

	private static boolean cycles(ResolvedOntology o, Entity e) {
		Set<Entity> seen = new HashSet<>();
		Entity current = e;
		while (current != null && current.extendsRef() != null) {
			if (!seen.add(current)) {
				return true;
			}
			current = o.entity(current.extendsRef(), current.origin()).orElse(null);
		}
		return false;
	}

	private static void claim(Map<String, String> owners, String field, Entity declaredBy, Entity entity, String at,
			List<OntologyProblem> problems) {
		String owner = declaredBy.origin() + "/" + declaredBy.name();
		String previous = owners.putIfAbsent(field, owner);
		if (previous != null && declaredBy == entity) {
			problems.add(new OntologyProblem(at, "field '" + field + "' is declared twice"
					+ (previous.equals(owner) ? "" : " (it is also inherited from " + previous.replace('/', ' ') + ")")));
		}
	}

	private void attribute(ResolvedOntology o, Entity e, Attribute a, String at, List<OntologyProblem> problems) {
		if (!FIELD_NAME.matcher(a.name()).matches()) {
			problems.add(new OntologyProblem(at, "attribute names start with a lower-case letter and use only letters and digits"));
		}
		if ((a.type() == null) == (a.vocab() == null)) {
			problems.add(new OntologyProblem(at, "give exactly one of 'type' (one of " + Attribute.TYPES
					+ ") or 'vocab' (a vocabulary name)"));
			return;
		}
		if (a.type() != null && !Attribute.TYPES.contains(a.type())) {
			problems.add(new OntologyProblem(at + ".type", "unknown type '" + a.type() + "'; expected one of " + Attribute.TYPES));
		}
		if (a.vocab() != null && o.vocabulary(a.vocab(), e.origin()).isEmpty()) {
			problems.add(new OntologyProblem(at + ".vocab", "unknown vocabulary '" + a.vocab() + "'; known: "
					+ knownVocabularies(o)));
		}
		if ((a.min() != null || a.max() != null) && !a.numeric()) {
			problems.add(new OntologyProblem(at, "'min' and 'max' apply only to integer and decimal attributes"));
		}
		if (a.min() != null && a.max() != null && a.min().compareTo(a.max()) > 0) {
			problems.add(new OntologyProblem(at, "min " + a.min().toPlainString() + " is greater than max " + a.max().toPlainString()));
		}
	}

	private void relation(ResolvedOntology o, Entity e, Relation r, String at, List<OntologyProblem> problems) {
		if (!FIELD_NAME.matcher(r.name()).matches()) {
			problems.add(new OntologyProblem(at, "relation names start with a lower-case letter and use only letters and digits"));
		}
		if (r.field() != null && !FIELD_NAME.matcher(r.field()).matches()) {
			problems.add(new OntologyProblem(at + ".field", "field names start with a lower-case letter and use only letters and digits"));
		}
		if (r.to() == null) {
			problems.add(new OntologyProblem(at, "'to' is required: the entity this relation points at"));
		}
		else if (o.entity(r.to(), e.origin()).isEmpty()) {
			problems.add(new OntologyProblem(at + ".to", "unknown entity '" + r.to() + "'; known: " + knownEntities(o)));
		}
		if ((r.min() != null || r.max() != null) && !r.many()) {
			problems.add(new OntologyProblem(at, "'min' and 'max' apply only to relations with many: true"));
		}
		if (r.min() != null && r.max() != null && r.min() > r.max()) {
			problems.add(new OntologyProblem(at, "min " + r.min() + " is greater than max " + r.max()));
		}
		if ((r.min() != null && r.min() < 0) || (r.max() != null && r.max() < 0)) {
			problems.add(new OntologyProblem(at, "min and max cannot be negative"));
		}
	}

	private void vocabulary(Vocabulary v, List<OntologyProblem> problems) {
		String at = "vocabularies." + v.name();
		if (!ENTITY_NAME.matcher(v.name()).matches()) {
			problems.add(new OntologyProblem(at, "vocabulary names start with a capital letter and use only letters and digits"));
		}
		if (v.terms().isEmpty()) {
			problems.add(new OntologyProblem(at, "has no values"));
		}
		Map<String, String> synonymOwner = new HashMap<>();
		for (Vocabulary.Term t : v.terms()) {
			if (!CODE.matcher(t.code()).matches()) {
				problems.add(new OntologyProblem(at + "." + t.code(), "codes are UPPER_CASE letters, digits and underscores"));
			}
			for (String synonym : t.synonyms()) {
				String key = synonym.strip().toLowerCase(Locale.ROOT);
				String other = synonymOwner.putIfAbsent(key, t.code());
				if (other != null && !other.equals(t.code())) {
					problems.add(new OntologyProblem(at + "." + t.code() + ".synonyms", "'" + synonym
							+ "' is also a synonym of " + other + "; a word can mean only one value"));
				}
			}
		}
	}

	private void constraint(ResolvedOntology o, Constraint c, String at, Set<String> ids, List<OntologyProblem> problems) {
		if (c.id() == null || c.id().isBlank()) {
			problems.add(new OntologyProblem(at, "'id' is required"));
		}
		else {
			at = "constraints." + c.id();
			if (!ids.add(c.id())) {
				problems.add(new OntologyProblem(at, "constraint id '" + c.id() + "' is used twice"));
			}
		}
		if (c.when().isEmpty()) {
			problems.add(new OntologyProblem(at + ".when", "needs at least one condition"));
		}
		if (c.require().isEmpty()) {
			problems.add(new OntologyProblem(at + ".require", "needs at least one condition"));
		}
		String where = at;
		c.when().forEach((path, value) -> condition(o, path, value, where + ".when", problems));
		c.require().forEach((path, value) -> condition(o, path, value, where + ".require", problems));
	}

	/** {@code Entity.field} with a value: notEmpty, empty, a code or list of codes, or a literal. */
	private void condition(ResolvedOntology o, String path, Object value, String at, List<OntologyProblem> problems) {
		String[] parts = path.split("\\.");
		if (parts.length != 2) {
			problems.add(new OntologyProblem(at, "'" + path + "' should be Entity.field, such as Case.urgency"));
			return;
		}
		Optional<Entity> entity = o.entity(parts[0]);
		if (entity.isEmpty()) {
			problems.add(new OntologyProblem(at, "'" + path + "': unknown entity '" + parts[0] + "'"));
			return;
		}
		Optional<ResolvedOntology.Declared<Attribute>> attribute = o.attribute(entity.get(), parts[1]);
		if (attribute.isEmpty()) {
			if (o.relation(entity.get(), parts[1]).isEmpty()) {
				problems.add(new OntologyProblem(at, "'" + path + "': " + parts[0] + " has no field '" + parts[1] + "'"));
			}
			else if (!(Constraint.NOT_EMPTY.equals(value) || Constraint.EMPTY.equals(value))) {
				problems.add(new OntologyProblem(at, "'" + path + "' is a relation; it can only be " + Constraint.NOT_EMPTY
						+ " or " + Constraint.EMPTY));
			}
			return;
		}
		if (Constraint.NOT_EMPTY.equals(value) || Constraint.EMPTY.equals(value)) {
			return;
		}
		Optional<Vocabulary> vocab = o.vocabulary(attribute.get());
		if (vocab.isPresent()) {
			List<?> codes = value instanceof List<?> l ? l : List.of(String.valueOf(value));
			for (Object code : codes) {
				if (!vocab.get().codes().contains(String.valueOf(code))) {
					problems.add(new OntologyProblem(at, "'" + path + "': '" + code + "' is not a value of "
							+ vocab.get().name() + " " + vocab.get().codes()));
				}
			}
		}
	}

	private void projection(ResolvedOntology o, Projection p, List<OntologyProblem> problems) {
		String at = "projections." + p.name();
		Optional<Entity> root = o.entity(p.root());
		if (p.root() == null) {
			problems.add(new OntologyProblem(at + ".root", "is required: the entity the schema starts from"));
			return;
		}
		if (root.isEmpty()) {
			problems.add(new OntologyProblem(at + ".root", "unknown entity '" + p.root() + "'; known: " + knownEntities(o)));
			return;
		}
		Set<Entity> embed = new LinkedHashSet<>();
		Set<Entity> reference = new LinkedHashSet<>();
		for (String name : p.embed()) {
			o.entity(name).ifPresentOrElse(embed::add, () -> problems.add(new OntologyProblem(at + ".embed",
					"unknown entity '" + name + "'")));
		}
		for (String name : p.reference()) {
			o.entity(name).ifPresentOrElse(e -> {
				if (embed.contains(e)) {
					problems.add(new OntologyProblem(at, "'" + name + "' cannot be both embedded and referenced"));
				}
				reference.add(e);
			}, () -> problems.add(new OntologyProblem(at + ".reference", "unknown entity '" + name + "'")));
		}

		// Everything named must be reachable from the root through relations,
		// passing only through embedded entities.
		Set<Entity> reached = new HashSet<>();
		Deque<Entity> queue = new ArrayDeque<>(List.of(root.get()));
		Set<Entity> visited = new HashSet<>();
		while (!queue.isEmpty()) {
			Entity current = queue.poll();
			if (!visited.add(current)) {
				continue;
			}
			for (ResolvedOntology.Declared<Relation> r : o.relations(current)) {
				o.target(r).ifPresent(target -> {
					if (embed.contains(target)) {
						reached.add(target);
						queue.add(target);
					}
					else if (reference.contains(target)) {
						reached.add(target);
					}
				});
			}
		}
		for (Entity e : embed) {
			if (!reached.contains(e)) {
				problems.add(new OntologyProblem(at + ".embed", "no relation from " + p.root()
						+ " (or the entities it embeds) leads to " + e.name()));
			}
		}
		for (Entity e : reference) {
			if (!reached.contains(e)) {
				problems.add(new OntologyProblem(at + ".reference", "no relation from " + p.root()
						+ " (or the entities it embeds) leads to " + e.name()));
			}
		}

		for (String path : p.include()) {
			String[] parts = path.split("\\.");
			if (parts.length > 2) {
				problems.add(new OntologyProblem(at + ".include", "'" + path + "' goes deeper than field.subfield"));
				continue;
			}
			Optional<Entity> owner = fieldTarget(o, root.get(), parts[0], embed, reference, path, at, problems);
			if (parts.length == 2 && owner.isPresent()) {
				if (!embed.contains(owner.get())) {
					problems.add(new OntologyProblem(at + ".include", "'" + path + "': " + parts[0]
							+ " is not embedded, so its fields cannot be chosen"));
				}
				else if (o.attribute(owner.get(), parts[1]).isEmpty() && o.relation(owner.get(), parts[1]).isEmpty()) {
					problems.add(new OntologyProblem(at + ".include", "'" + path + "': " + owner.get().name()
							+ " has no field '" + parts[1] + "'"));
				}
			}
		}
	}

	/** Checks the first part of an include path; returns the entity a relation leads to, if it is one. */
	private static Optional<Entity> fieldTarget(ResolvedOntology o, Entity root, String field, Set<Entity> embed,
			Set<Entity> reference, String path, String at, List<OntologyProblem> problems) {
		if (o.attribute(root, field).isPresent()) {
			return Optional.empty();
		}
		Optional<ResolvedOntology.Declared<Relation>> relation = o.relation(root, field);
		if (relation.isEmpty()) {
			problems.add(new OntologyProblem(at + ".include", "'" + path + "': " + root.name() + " has no field '" + field + "'"));
			return Optional.empty();
		}
		Optional<Entity> target = o.target(relation.get());
		if (target.isPresent() && !embed.contains(target.get()) && !reference.contains(target.get())) {
			problems.add(new OntologyProblem(at + ".include", "'" + path + "' leads to " + target.get().name()
					+ ", which the projection neither embeds nor references"));
		}
		return target;
	}

	private static List<String> knownEntities(ResolvedOntology o) {
		List<String> names = new ArrayList<>(o.own().entities().keySet());
		if (!o.isCore()) {
			names.addAll(prefixed(o.core().entities().keySet()));
		}
		return names;
	}

	private static List<String> knownVocabularies(ResolvedOntology o) {
		List<String> names = new ArrayList<>(o.own().vocabularies().keySet());
		if (!o.isCore()) {
			names.addAll(prefixed(o.core().vocabularies().keySet()));
		}
		return names;
	}

	private static List<String> prefixed(Set<String> names) {
		return names.stream().map(n -> ResolvedOntology.CORE_PREFIX + n).toList();
	}
}

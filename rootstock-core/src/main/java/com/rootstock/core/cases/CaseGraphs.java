package com.rootstock.core.cases;

import com.rootstock.core.graph.CaseGraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The compiled graphs of each pack that has any: the one a case starts with
 * (trigger submit) and any it goes on with (trigger decision).
 */
public final class CaseGraphs {

	private final Map<String, List<CaseGraph>> byPack = new LinkedHashMap<>();

	public CaseGraphs(Collection<CaseGraph> graphs) {
		graphs.forEach(g -> byPack.computeIfAbsent(g.pack(), k -> new ArrayList<>()).add(g));
	}

	/** The graph a new case of this pack starts with. */
	public Optional<CaseGraph> forPack(String pack) {
		return byPack.getOrDefault(pack, List.of()).stream()
				.filter(g -> !g.definition().graph().trigger().decision()).findFirst();
	}

	/** A graph by pack and name. */
	public Optional<CaseGraph> graph(String pack, String name) {
		return byPack.getOrDefault(pack, List.of()).stream().filter(g -> g.name().equals(name)).findFirst();
	}

	/** The graph to start a case with when no pack is named: the only pack's. */
	public Optional<CaseGraph> only() {
		return byPack.size() == 1 ? forPack(byPack.keySet().iterator().next()) : Optional.empty();
	}

	/** Every graph of every pack. */
	public List<CaseGraph> all() {
		return byPack.values().stream().flatMap(List::stream).toList();
	}

	/** The graph each pack's cases start with. */
	public List<CaseGraph> entries() {
		return byPack.keySet().stream().map(this::forPack).flatMap(Optional::stream).toList();
	}
}

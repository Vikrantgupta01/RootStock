package com.rootstock.core.cases;

import com.rootstock.core.graph.CaseGraph;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The compiled graph of each pack that has one, by pack name. */
public final class CaseGraphs {

	private final Map<String, CaseGraph> byPack = new LinkedHashMap<>();

	public CaseGraphs(Collection<CaseGraph> graphs) {
		graphs.forEach(g -> byPack.put(g.pack(), g));
	}

	public Optional<CaseGraph> forPack(String pack) {
		return Optional.ofNullable(byPack.get(pack));
	}

	/** The graph to use when no pack is named: the only one there is. */
	public Optional<CaseGraph> only() {
		return byPack.size() == 1 ? Optional.of(byPack.values().iterator().next()) : Optional.empty();
	}

	public List<CaseGraph> all() {
		return List.copyOf(byPack.values());
	}
}

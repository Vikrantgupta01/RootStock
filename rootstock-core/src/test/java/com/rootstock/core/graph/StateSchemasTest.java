package com.rootstock.core.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.state.Channel;
import org.junit.jupiter.api.Test;

class StateSchemasTest {

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object update(GraphDefinition.Reducer reducer, Object current, Object update) {
		Channel channel = StateSchemas.channel(reducer);
		return channel.update("key", current, update);
	}

	@Test
	void replaceTakesTheNewValue() {
		assertThat(update(GraphDefinition.Reducer.REPLACE, Map.of("a", 1), Map.of("b", 2))).isEqualTo(Map.of("b", 2));
		assertThat(update(GraphDefinition.Reducer.REPLACE, List.of("x"), List.of())).isEqualTo(List.of());
	}

	@Test
	void mergeKeepsOldKeysAndLetsTheUpdateWin() {
		assertThat(update(GraphDefinition.Reducer.MERGE, Map.of("a", 1, "b", 2), Map.of("b", 3, "c", 4)))
				.isEqualTo(Map.of("a", 1, "b", 3, "c", 4));
		assertThat(update(GraphDefinition.Reducer.MERGE, null, Map.of("a", 1))).isEqualTo(Map.of("a", 1));
	}

	@Test
	void appendAddsItemsAndKeepsDuplicates() {
		assertThat(update(GraphDefinition.Reducer.APPEND, List.of("a"), List.of("b", "a"))).isEqualTo(List.of("a", "b", "a"));
		assertThat(update(GraphDefinition.Reducer.APPEND, null, List.of("a"))).isEqualTo(List.of("a"));
	}
}

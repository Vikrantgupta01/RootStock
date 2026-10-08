package com.rootstock.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Configuration mistakes stop the app at startup, before any call is made. */
class ToolCatalogTest {

	private final Map<String, ToolDefinition> tools = Map.of(
			"find_household", new ToolDefinition("find_household", "client", "find_household", ToolAccess.READ),
			"create_case", new ToolDefinition("create_case", "client", "create_case", ToolAccess.WRITE));

	@Test
	void anAllowlistNamingAnUnknownToolIsRejected() {
		assertThatThrownBy(() -> new ToolCatalog(tools, Map.of("enrich", List.of("find_houshold")), Set.of()))
				.hasMessageContaining("unknown tool 'find_houshold'");
	}

	@Test
	void aWriteToolOutsideAWriteNodeIsRejectedWhateverTheConfigSays() {
		assertThatThrownBy(() -> new ToolCatalog(tools, Map.of("enrich", List.of("create_case")), Set.of("commit")))
				.hasMessageContaining("Write tool 'create_case'")
				.hasMessageContaining("'enrich'");
	}

	@Test
	void aWriteToolInAWriteNodeIsAccepted() {
		assertThatCode(() -> new ToolCatalog(tools, Map.of("commit", List.of("create_case")), Set.of("commit")))
				.doesNotThrowAnyException();
	}

	@Test
	void listsWhatANodeMayCall() {
		ToolCatalog catalog = new ToolCatalog(tools, Map.of("enrich", List.of("find_household")), Set.of());

		assertThat(catalog.allowed("enrich")).containsExactly("find_household");
		assertThat(catalog.allowed("nobody")).isEmpty();
	}
}

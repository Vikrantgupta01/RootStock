package com.sinewlabs.vinnies.mcp;

import com.sinewlabs.vinnies.mcp.assistance.AssistanceRepository;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader;
import com.sinewlabs.vinnies.mcp.guideline.AssistanceGuideline;
import com.sinewlabs.vinnies.mcp.guideline.AssistanceGuidelineRepository;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import com.sinewlabs.vinnies.mcp.household.Household;
import com.sinewlabs.vinnies.mcp.household.HouseholdRepository;
import com.sinewlabs.vinnies.mcp.household.Relationship;
import com.sinewlabs.vinnies.mcp.localservice.LocalServiceRepository;
import com.sinewlabs.vinnies.mcp.support.McpTestClient;
import com.sinewlabs.vinnies.mcp.support.TestJwt;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Talks to the running server the way MCP Inspector or Rootstock will: a real
 * MCP client over Streamable HTTP at /mcp. Proves the annotation scanner found
 * the tool and that it is callable end to end, not just that the method works.
 *
 * <p>Checks the MCP wiring only, so the database auto-configuration is left out,
 * beans that need a database are mocked, and the test needs no AWS. Tools that
 * read data are tested against RDS in a throwaway schema instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = """
		spring.autoconfigure.exclude=\
		org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,\
		org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,\
		org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
		vinnies.auth.issuer-uri=https://cognito-idp.test.example/us-east-1_TEST
		""")
@Import(TestJwt.Config.class)
class McpEndpointTest {

	@LocalServerPort
	int port;

	@MockitoBean
	DemoDataLoader demoDataLoader;

	@MockitoBean
	HouseholdRepository households;

	@MockitoBean
	AssistanceRepository assistance;

	@MockitoBean
	AssistanceGuidelineRepository guidelines;

	@MockitoBean
	LocalServiceRepository localServices;

	McpSyncClient client;

	@BeforeEach
	void connect() {
		client = McpTestClient.connect(port, TestJwt.access("vinnies/read"));
	}

	@AfterEach
	void disconnect() {
		client.closeGracefully();
	}

	@Test
	void serverIdentifiesItself() {
		assertThat(client.getServerInfo().name()).isEqualTo("vinnies");
	}

	@Test
	void listsPingAsReadOnly() {
		McpSchema.Tool ping = client.listTools().tools().stream()
				.filter(t -> t.name().equals("ping"))
				.findFirst()
				.orElseThrow();

		assertThat(ping.description()).contains("reachable");
		assertThat(ping.annotations().readOnlyHint()).isTrue();
	}

	@Test
	void callsPing() {
		McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("ping", Map.of()));

		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(result.content()).singleElement()
				.isInstanceOfSatisfying(McpSchema.TextContent.class,
						text -> assertThat(text.text()).contains("\"message\":\"pong\"")
								.contains("\"server\":\"vinnies-mcp-server\""));
	}

	@Test
	void findHouseholdTakesNameAndSuburbWithPhoneOptional() {
		McpSchema.Tool tool = client.listTools().tools().stream()
				.filter(t -> t.name().equals("find_household"))
				.findFirst()
				.orElseThrow();

		// MCP SDK 2.0: the input schema is the raw JSON Schema as a map.
		Map<String, Object> schema = tool.inputSchema();
		List<String> properties = ((Map<?, ?>) schema.get("properties")).keySet().stream()
				.map(String::valueOf).toList();
		List<String> required = ((List<?>) schema.get("required")).stream().map(String::valueOf).toList();
		assertThat(properties).containsExactlyInAnyOrder("name", "suburb", "phone");
		assertThat(required).containsExactlyInAnyOrder("name", "suburb");
		assertThat(tool.annotations().readOnlyHint()).isTrue();
	}

	@Test
	void findHouseholdReturnsRankedMatchesWithoutContactDetails() {
		Household tran = new Household(UUID.randomUUID(), "HH-0001", "Tran", "Blacktown", "2148", "02 5550 0101",
				true, Instant.EPOCH);
		tran.addMember(UUID.randomUUID(), "Linh", "Tran", Relationship.PRIMARY_CONTACT, 1984);
		BDDMockito.given(households.findAllWithMembers()).willReturn(List.of(tran));

		McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("find_household",
				Map.of("name", "Linh Tran", "suburb", "Blacktown")));

		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(result.content()).singleElement()
				.isInstanceOfSatisfying(McpSchema.TextContent.class, text -> assertThat(text.text())
						.contains("\"householdRef\":\"HH-0001\"")
						.contains("\"matchScore\":0.95")
						.doesNotContain("5550"));
	}

	@Test
	void getAssistanceHistoryTakesAHouseholdRefAndAWholeNumberOfDays() {
		McpSchema.Tool tool = client.listTools().tools().stream()
				.filter(t -> t.name().equals("get_assistance_history"))
				.findFirst()
				.orElseThrow();

		Map<String, Object> schema = tool.inputSchema();
		Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
		List<String> required = ((List<?>) schema.get("required")).stream().map(String::valueOf).toList();
		assertThat(required).containsExactlyInAnyOrder("householdRef", "sinceDays");
		assertThat(((Map<?, ?>) properties.get("sinceDays")).get("type")).isEqualTo("integer");
		assertThat(tool.annotations().readOnlyHint()).isTrue();
	}

	@Test
	void getAssistanceGuidelinesOffersOnlyTheKnownTypes() {
		McpSchema.Tool tool = client.listTools().tools().stream()
				.filter(t -> t.name().equals("get_assistance_guidelines"))
				.findFirst()
				.orElseThrow();

		Map<?, ?> type = (Map<?, ?>) ((Map<?, ?>) tool.inputSchema().get("properties")).get("assistanceType");
		List<String> allowed = ((List<?>) type.get("enum")).stream().map(String::valueOf).toList();
		assertThat(allowed).containsExactlyInAnyOrder("FOOD", "ENERGY_BILL", "RENT");
	}

	@Test
	void publishesTheGuidelinesAsResources() {
		assertThat(client.listResources().resources()).extracting(McpSchema.Resource::uri)
				.contains("vinnies://guidelines");
		assertThat(client.listResourceTemplates().resourceTemplates())
				.extracting(McpSchema.ResourceTemplate::uriTemplate)
				.contains("vinnies://guidelines/{assistanceType}");
	}

	@Test
	void readsOneGuidelineResourceAsMarkdown() {
		BDDMockito.given(guidelines.findById(NeedCategory.RENT)).willReturn(Optional.of(new AssistanceGuideline(
				NeedCategory.RENT, "Rent assistance", "Pay the agent directly.", new BigDecimal("600.00"), 180,
				LocalDate.of(2026, 7, 1))));

		McpSchema.ReadResourceResult result = client.readResource(
				new McpSchema.ReadResourceRequest("vinnies://guidelines/RENT"));

		assertThat(result.contents()).singleElement()
				.isInstanceOfSatisfying(McpSchema.TextResourceContents.class, text -> {
					assertThat(text.mimeType()).isEqualTo("text/markdown");
					assertThat(text.text()).contains("# Rent assistance", "Limit per visit: $600.00");
				});
	}

	@Test
	void searchLocalServicesTakesAKnownNeedAndASuburb() {
		McpSchema.Tool tool = client.listTools().tools().stream()
				.filter(t -> t.name().equals("search_local_services"))
				.findFirst()
				.orElseThrow();

		Map<?, ?> properties = (Map<?, ?>) tool.inputSchema().get("properties");
		List<String> required = ((List<?>) tool.inputSchema().get("required")).stream().map(String::valueOf).toList();
		List<String> needs = ((List<?>) ((Map<?, ?>) properties.get("needType")).get("enum")).stream()
				.map(String::valueOf).toList();
		assertThat(required).containsExactlyInAnyOrder("needType", "suburb");
		assertThat(needs).containsExactlyInAnyOrder("FOOD", "ENERGY_BILL", "RENT");
		assertThat(tool.annotations().readOnlyHint()).isTrue();
	}
}

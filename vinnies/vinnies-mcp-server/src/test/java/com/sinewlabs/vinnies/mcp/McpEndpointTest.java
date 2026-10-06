package com.sinewlabs.vinnies.mcp;

import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader;
import com.sinewlabs.vinnies.mcp.household.Household;
import com.sinewlabs.vinnies.mcp.household.HouseholdRepository;
import com.sinewlabs.vinnies.mcp.household.Relationship;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
		""")
class McpEndpointTest {

	@LocalServerPort
	int port;

	@MockitoBean
	DemoDataLoader demoDataLoader;

	@MockitoBean
	HouseholdRepository households;

	McpSyncClient client;

	@BeforeEach
	void connect() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
				.endpoint("/mcp")
				.build();
		client = McpClient.sync(transport).build();
		client.initialize();
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
}

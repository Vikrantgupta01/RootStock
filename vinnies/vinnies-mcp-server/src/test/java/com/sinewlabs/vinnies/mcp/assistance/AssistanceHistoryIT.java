package com.sinewlabs.vinnies.mcp.assistance;

import static org.assertj.core.api.Assertions.assertThat;

import com.sinewlabs.vinnies.mcp.ClockConfig;
import com.sinewlabs.vinnies.mcp.demodata.DemoData;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataGenerator;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader;
import com.sinewlabs.vinnies.mcp.support.CognitoTokens;
import com.sinewlabs.vinnies.mcp.support.McpTestClient;
import com.sinewlabs.vinnies.mcp.support.ThrowawaySchemaConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * get_assistance_history over MCP against the seeded data on RDS. The expected
 * history is computed from the generator itself, so "correct" means exactly the
 * rows the seed wrote for that household and window: no more, no fewer, newest
 * first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ThrowawaySchemaConfig.class)
class AssistanceHistoryIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	record Entry(LocalDate date, String type, BigDecimal amountAud) {
	}

	record History(String householdRef, LocalDate from, LocalDate to, List<Entry> assistance) {
	}

	@LocalServerPort
	int port;

	@Autowired
	DemoDataLoader demoData;

	McpSyncClient client;

	@BeforeEach
	void seedAndConnect() {
		demoData.reset();
		client = McpTestClient.connect(port, CognitoTokens.read());
	}

	@AfterEach
	void disconnect() {
		client.closeGracefully();
	}

	@ParameterizedTest(name = "{0}, last {1} days")
	@CsvSource({ "HH-0001, 30", "HH-0001, 90", "HH-0001, 365", "HH-0005, 365", "HH-0017, 180", "HH-0042, 3650" })
	void returnsExactlyTheSeededHistoryNewestFirst(String ref, int sinceDays) {
		History history = history(ref, sinceDays);

		List<Entry> expected = expectedHistory(ref, sinceDays);
		assertThat(history.householdRef()).isEqualTo(ref);
		assertThat(history.assistance()).hasSameSizeAs(expected);
		for (int i = 0; i < expected.size(); i++) {
			Entry actual = history.assistance().get(i);
			assertThat(actual.date()).isEqualTo(expected.get(i).date());
			assertThat(actual.type()).isEqualTo(expected.get(i).type());
			assertThat(actual.amountAud()).isEqualByComparingTo(expected.get(i).amountAud());
		}
	}

	@Test
	void theFirstDemoHouseholdHasItsRecentEnergyHelpInTheLastMonth() {
		History history = history("HH-0001", 30);

		assertThat(history.assistance())
				.filteredOn(e -> e.date().equals(today().minusDays(21)))
				.extracting(Entry::type)
				.contains("ENERGY_BILL", "FOOD");
	}

	@Test
	void theNewHouseholdHasNoHistory() {
		assertThat(history("HH-0004", 3650).assistance()).isEmpty();
	}

	@Test
	void anUnknownHouseholdIsReportedAsAnError() {
		McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("get_assistance_history",
				Map.of("householdRef", "HH-9999", "sinceDays", 90)));

		assertThat(result.isError()).isTrue();
		assertThat(result.content().toString()).contains("HH-9999");
	}

	private History history(String ref, int sinceDays) {
		String text = McpTestClient.callText(client, "get_assistance_history",
				Map.of("householdRef", ref, "sinceDays", sinceDays));
		return JSON.readValue(text, History.class);
	}

	private static List<Entry> expectedHistory(String ref, int sinceDays) {
		DemoData data = DemoDataGenerator.generate(today());
		var householdId = data.households().stream().filter(h -> h.ref().equals(ref)).findFirst().orElseThrow().id();
		LocalDate from = today().minusDays(sinceDays);
		return data.assistance().stream()
				.filter(a -> a.householdId().equals(householdId) && !a.assistedOn().isBefore(from))
				.sorted(Comparator.comparing(DemoData.AssistanceRow::assistedOn)
						.thenComparing(DemoData.AssistanceRow::ref).reversed())
				.map(a -> new Entry(a.assistedOn(), a.category().name(), a.amountAud()))
				.toList();
	}

	private static LocalDate today() {
		return LocalDate.now(ClockConfig.SYDNEY);
	}
}

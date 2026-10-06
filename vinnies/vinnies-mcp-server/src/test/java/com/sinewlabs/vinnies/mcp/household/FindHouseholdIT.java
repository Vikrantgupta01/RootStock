package com.sinewlabs.vinnies.mcp.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader;
import com.sinewlabs.vinnies.mcp.support.McpTestClient;
import com.sinewlabs.vinnies.mcp.support.ThrowawaySchemaConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * find_household end to end: a real MCP client over HTTP, the real server, and
 * the seeded demo data on RDS, in a throwaway schema. These are the demo's own
 * queries, so a passing run means the Inspector demo will show these results.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ThrowawaySchemaConfig.class)
class FindHouseholdIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	record Match(String householdRef, String primaryContact, String suburb, double matchScore,
			List<String> matchedOn, String matchedName) {
	}

	record Matches(List<Match> matches) {
	}

	@LocalServerPort
	int port;

	@Autowired
	DemoDataLoader demoData;

	McpSyncClient client;

	@BeforeEach
	void seedAndConnect() {
		demoData.reset();
		client = McpTestClient.connect(port);
	}

	@AfterEach
	void disconnect() {
		client.closeGracefully();
	}

	@Test
	void fullNameAndSuburbFindTheHousehold() {
		List<Match> matches = find(Map.of("name", "Linh Tran", "suburb", "Blacktown"));

		assertThat(matches).extracting(Match::householdRef).containsExactly("HH-0001");
		assertThat(matches.get(0).matchScore()).isEqualTo(0.95);
		assertThat(matches.get(0).matchedOn()).containsExactly("NAME", "SUBURB");
	}

	@Test
	void suburbRanksTheRightOfTwoSameNameHouseholdsFirst() {
		List<Match> matches = find(Map.of("name", "Tran", "suburb", "Mount Druitt"));

		assertThat(matches).extracting(Match::householdRef, Match::matchScore)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("HH-0002", 0.95),
						org.assertj.core.groups.Tuple.tuple("HH-0001", 0.75));
	}

	@Test
	void misspellingsStillFindBothNearTwins() {
		List<Match> matches = find(Map.of("name", "Katherine Smith", "suburb", "Paramatta"));

		assertThat(matches).extracting(Match::householdRef).containsExactly("HH-0003", "HH-0004");
		assertThat(matches).allSatisfy(m -> assertThat(m.matchScore()).isBetween(0.9, 0.95));
	}

	@Test
	void anExactPhoneWins() {
		List<Match> matches = find(Map.of("name", "Katherine Smyth", "suburb", "Parramatta",
				"phone", "+61 2 5550 0103"));

		assertThat(matches.get(0).householdRef()).isEqualTo("HH-0003");
		assertThat(matches.get(0).matchScore()).isEqualTo(1.0);
		assertThat(matches.get(0).matchedOn()).contains("PHONE");
		assertThat(matches.get(1).matchScore()).isLessThan(1.0);
	}

	@Test
	void anUnknownNameFindsNothing() {
		assertThat(find(Map.of("name", "Zebedee Quinn", "suburb", "Parramatta"))).isEmpty();
	}

	@Test
	void neverReturnsPhoneNumbers() {
		String raw = McpTestClient.callText(client, "find_household",
				Map.of("name", "Tran", "suburb", "Blacktown", "phone", "02 5550 0101"));

		assertThat(raw).doesNotContain("5550").doesNotContainIgnoringCase("phone\":");
	}

	private List<Match> find(Map<String, Object> arguments) {
		String text = McpTestClient.callText(client, "find_household", arguments);
		return JSON.readValue(text, Matches.class).matches();
	}
}

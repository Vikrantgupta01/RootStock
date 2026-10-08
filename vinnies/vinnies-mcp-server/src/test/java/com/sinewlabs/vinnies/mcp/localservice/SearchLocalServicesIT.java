package com.sinewlabs.vinnies.mcp.localservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.sinewlabs.vinnies.mcp.ClockConfig;
import com.sinewlabs.vinnies.mcp.demodata.DemoData.ServiceRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataGenerator;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader;
import com.sinewlabs.vinnies.mcp.support.CognitoTokens;
import com.sinewlabs.vinnies.mcp.support.McpTestClient;
import com.sinewlabs.vinnies.mcp.support.ThrowawaySchemaConfig;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;
import io.modelcontextprotocol.client.McpSyncClient;
import java.time.LocalDate;
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
 * search_local_services over MCP against the seeded services on RDS: for every
 * suburb and every need, exactly the services the seed put there, with the
 * address, hours and eligibility a referral needs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ThrowawaySchemaConfig.class)
class SearchLocalServicesIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	record Service(String serviceRef, String name, String needType, String address, String suburb, String phone,
			String hours, String eligibility, boolean inSuburb) {
	}

	record Result(String needType, String suburb, List<Service> services) {
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

	@Test
	void everySuburbAndNeedReturnsExactlyTheSeededServices() {
		List<ServiceRow> seeded = DemoDataGenerator.generate(LocalDate.now(ClockConfig.SYDNEY)).services();
		List<String> suburbs = seeded.stream().map(ServiceRow::suburb).distinct().toList();
		assertThat(suburbs).hasSize(12);

		for (String suburb : suburbs) {
			for (NeedCategory need : NeedCategory.values()) {
				List<ServiceRow> expected = seeded.stream()
						.filter(s -> s.suburb().equals(suburb) && s.needCategory() == need)
						.toList();
				List<Service> found = search(need.name(), suburb).services();

				assertThat(found).as("%s in %s", need, suburb)
						.extracting(Service::serviceRef)
						.containsExactlyInAnyOrderElementsOf(expected.stream().map(ServiceRow::ref).toList());
				assertThat(found).allSatisfy(s -> {
					assertThat(s.inSuburb()).isTrue();
					assertThat(s.address()).contains(suburb + " NSW");
					assertThat(s.hours()).isNotBlank();
					assertThat(s.eligibility()).isNotBlank();
					assertThat(s.phone()).matches("02 7010 \\d{4}");
				});
			}
		}
	}

	@Test
	void aMisspeltSuburbStillFindsItsServices() {
		assertThat(search("ENERGY_BILL", "Paramatta").services())
				.singleElement()
				.satisfies(s -> assertThat(s.suburb()).isEqualTo("Parramatta"));
	}

	@Test
	void aSuburbWithNoServicesOffersOthersFlaggedAsElsewhere() {
		Result result = search("RENT", "Bondi");

		assertThat(result.services()).hasSize(5)
				.allSatisfy(s -> {
					assertThat(s.inSuburb()).isFalse();
					assertThat(s.needType()).isEqualTo("RENT");
				});
	}

	private Result search(String needType, String suburb) {
		String text = McpTestClient.callText(client, "search_local_services",
				Map.of("needType", needType, "suburb", suburb));
		return JSON.readValue(text, Result.class);
	}
}

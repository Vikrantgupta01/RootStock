package com.sinewlabs.vinnies.mcp.guideline;

import static org.assertj.core.api.Assertions.assertThat;

import com.sinewlabs.vinnies.mcp.demodata.DemoData.GuidelineRow;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataGenerator;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader;
import com.sinewlabs.vinnies.mcp.support.CognitoTokens;
import com.sinewlabs.vinnies.mcp.support.McpTestClient;
import com.sinewlabs.vinnies.mcp.support.ThrowawaySchemaConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;
import com.sinewlabs.vinnies.mcp.vocabulary.NeedCategory;

/**
 * get_assistance_guidelines and the guideline resources over MCP, against the
 * seeded rows on RDS. The point of having both is that reviewers (resource) and
 * rules (tool) read the same source, so this checks they agree, type by type.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ThrowawaySchemaConfig.class)
class GuidelinesIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	record Guideline(String assistanceType, String title, String guideline, BigDecimal limitPerVisitAud,
			int repeatWindowDays, LocalDate effectiveFrom, String resourceUri) {
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

	@ParameterizedTest
	@EnumSource(NeedCategory.class)
	void toolReturnsTheSeededGuidelineAndTheResourceSaysTheSame(NeedCategory type) {
		GuidelineRow seeded = DemoDataGenerator.GUIDELINES.stream()
				.filter(g -> g.assistanceType() == type).findFirst().orElseThrow();

		Guideline tool = JSON.readValue(McpTestClient.callText(client, "get_assistance_guidelines",
				Map.of("assistanceType", type.name())), Guideline.class);

		assertThat(tool.assistanceType()).isEqualTo(type.name());
		assertThat(tool.limitPerVisitAud()).isEqualByComparingTo(seeded.limitPerVisitAud());
		assertThat(tool.repeatWindowDays()).isEqualTo(seeded.repeatWindowDays());
		assertThat(tool.guideline()).isEqualTo(seeded.guidelineText());
		assertThat(tool.effectiveFrom()).isEqualTo(seeded.effectiveFrom());

		String resource = readText(tool.resourceUri());
		assertThat(resource).contains("# " + tool.title(),
				"Limit per visit: $" + tool.limitPerVisitAud().toPlainString(),
				"Repeat window: " + tool.repeatWindowDays() + " days",
				tool.guideline());
	}

	@Test
	void theAllGuidelinesResourceCoversEveryType() {
		String all = readText("vinnies://guidelines");

		DemoDataGenerator.GUIDELINES.forEach(g -> assertThat(all).contains("# " + g.title()));
	}

	@Test
	void anUnknownTypeIsRejected() {
		McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("get_assistance_guidelines",
				Map.of("assistanceType", "PHARMACY")));

		assertThat(result.isError()).isTrue();
	}

	private String readText(String uri) {
		McpSchema.ReadResourceResult result = client.readResource(new McpSchema.ReadResourceRequest(uri));
		return ((McpSchema.TextResourceContents) result.contents().get(0)).text();
	}
}

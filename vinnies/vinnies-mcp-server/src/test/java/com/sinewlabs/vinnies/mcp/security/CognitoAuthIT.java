package com.sinewlabs.vinnies.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader;
import com.sinewlabs.vinnies.mcp.support.CognitoTokens;
import com.sinewlabs.vinnies.mcp.support.McpTestClient;
import com.sinewlabs.vinnies.mcp.support.ThrowawaySchemaConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The demo's security story with real Cognito tokens (client credentials) and
 * the server's real decoder: keys from the pool's JWKS, issuer from
 * VINNIES_AUTH_ISSUER_URI. No local test keys anywhere in this class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ThrowawaySchemaConfig.class)
class CognitoAuthIT {

	@LocalServerPort
	int port;

	@Autowired
	DemoDataLoader demoData;

	@BeforeEach
	void seed() {
		demoData.reset();
	}

	@Test
	void withoutATokenTheServerRefusesTheSession() {
		assertThatThrownBy(() -> McpTestClient.connect(port, null))
				.hasRootCauseInstanceOf(McpHttpClientTransportAuthorizationException.class);
	}

	@Test
	void aRealReadTokenOpensEveryReadToolAndTheGuidelinesResource() {
		McpSyncClient client = McpTestClient.connect(port, CognitoTokens.read());
		try {
			assertThat(McpTestClient.callText(client, "find_household",
					Map.of("name", "Linh Tran", "suburb", "Blacktown"))).contains("HH-0001");
			assertThat(McpTestClient.callText(client, "get_assistance_history",
					Map.of("householdRef", "HH-0001", "sinceDays", 30))).contains("ENERGY_BILL");
			assertThat(McpTestClient.callText(client, "get_assistance_guidelines",
					Map.of("assistanceType", "ENERGY_BILL"))).contains("\"repeatWindowDays\":90");
			assertThat(McpTestClient.callText(client, "search_local_services",
					Map.of("needType", "FOOD", "suburb", "Blacktown"))).contains("SVC-001");
			McpSchema.ReadResourceResult guidelines = client.readResource(
					new McpSchema.ReadResourceRequest("vinnies://guidelines"));
			assertThat(((McpSchema.TextResourceContents) guidelines.contents().get(0)).text())
					.contains("# Food assistance");
		}
		finally {
			client.closeGracefully();
		}
	}

	@Test
	void aRealWriteOnlyTokenIsRefusedByReadToolsButStillConnects() {
		McpSyncClient client = McpTestClient.connect(port, CognitoTokens.write());
		try {
			McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("find_household",
					Map.of("name", "Linh Tran", "suburb", "Blacktown")));

			assertThat(result.isError()).isTrue();
			assertThat(result.content().toString()).contains("Access Denied");
			assertThat(result.content().toString()).doesNotContain("HH-0001");
		}
		finally {
			client.closeGracefully();
		}
	}
}

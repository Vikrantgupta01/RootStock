package com.sinewlabs.vinnies.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sinewlabs.vinnies.mcp.assistance.AssistanceRepository;
import com.sinewlabs.vinnies.mcp.demodata.DemoDataLoader;
import com.sinewlabs.vinnies.mcp.guideline.AssistanceGuidelineRepository;
import com.sinewlabs.vinnies.mcp.household.HouseholdRepository;
import com.sinewlabs.vinnies.mcp.localservice.LocalServiceRepository;
import com.sinewlabs.vinnies.mcp.support.McpTestClient;
import com.sinewlabs.vinnies.mcp.support.TestJwt;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Who gets in, end to end over MCP HTTP, with locally signed tokens checked by the
 * production validators (no Cognito, no database). Every refusal here would let
 * the wrong caller in if it ever passed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = """
		spring.autoconfigure.exclude=\
		org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,\
		org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,\
		org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration
		vinnies.auth.issuer-uri=https://cognito-idp.test.example/us-east-1_TEST
		""")
@Import(TestJwt.Config.class)
class McpSecurityTest {

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

	static List<String> tokensThatMustNotGetIn() {
		return List.of("none", "id token", "expired", "other issuer", "forged signature", "garbage");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("tokensThatMustNotGetIn")
	void refusedBeforeMcpSeesTheRequest(String kind) {
		String token = switch (kind) {
			case "none" -> null;
			case "id token" -> TestJwt.idToken();
			case "expired" -> TestJwt.expired("vinnies/read");
			case "other issuer" -> TestJwt.fromAnotherIssuer("vinnies/read");
			case "forged signature" -> TestJwt.forged("vinnies/read");
			default -> "not.a.jwt";
		};

		assertThatThrownBy(() -> McpTestClient.connect(port, token))
				.as("a session must not open with: %s", kind)
				.hasRootCauseInstanceOf(McpHttpClientTransportAuthorizationException.class);

		HttpResponse<String> raw = initializeOverRawHttp(token);
		assertThat(raw.statusCode()).as("HTTP status with: %s", kind).isEqualTo(401);
		assertThat(raw.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
	}

	@Test
	void publishesAccurateProtectedResourceMetadataWithoutAToken() throws Exception {
		HttpResponse<String> metadata = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/.well-known/oauth-protected-resource"))
						.build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(metadata.statusCode()).isEqualTo(200);
		assertThat(metadata.body())
				.contains("\"authorization_servers\":[\"" + TestJwt.ISSUER + "\"]")
				.contains("vinnies/read", "vinnies/write")
				.contains("\"tls_client_certificate_bound_access_tokens\":false");
	}

	@Test
	void theSameRawRequestWithAReadTokenIsAccepted() {
		assertThat(initializeOverRawHttp(TestJwt.access("vinnies/read")).statusCode()).isEqualTo(200);
	}

	@Test
	void aReadTokenCanListAndCallReadTools() {
		McpSyncClient client = McpTestClient.connect(port, TestJwt.access("vinnies/read"));
		try {
			assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("find_household");
			McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("find_household",
					Map.of("name", "Linh Tran", "suburb", "Blacktown")));
			assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		}
		finally {
			client.closeGracefully();
		}
	}

	@Test
	void aTokenWithoutTheReadScopeIsRefusedByEveryReadToolAndResource() {
		McpSyncClient client = McpTestClient.connect(port, TestJwt.access("vinnies/write"));
		try {
			Map<String, Map<String, Object>> calls = Map.of(
					"find_household", Map.of("name", "Linh Tran", "suburb", "Blacktown"),
					"get_assistance_history", Map.of("householdRef", "HH-0001", "sinceDays", 30),
					"get_assistance_guidelines", Map.of("assistanceType", "FOOD"),
					"search_local_services", Map.of("needType", "FOOD", "suburb", "Blacktown"));
			calls.forEach((tool, arguments) -> {
				McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
				assertThat(result.isError()).as(tool).isTrue();
				assertThat(result.content().toString()).as(tool).containsIgnoringCase("denied");
			});
			assertThatThrownBy(() -> client.readResource(new McpSchema.ReadResourceRequest("vinnies://guidelines")))
					.as("guidelines resource")
					.isInstanceOfSatisfying(io.modelcontextprotocol.spec.McpError.class, error -> assertThat(
							String.valueOf(error.getJsonRpcError())).contains("Access Denied"));
		}
		finally {
			client.closeGracefully();
		}
	}

	@Test
	void pingNeedsAValidTokenButNoScope() {
		McpSyncClient client = McpTestClient.connect(port, TestJwt.access("vinnies/write"));
		try {
			assertThat(McpTestClient.callText(client, "ping", Map.of())).contains("pong");
		}
		finally {
			client.closeGracefully();
		}
	}

	/** An MCP initialize request as plain HTTP, so the server's own status code is what's checked. */
	private HttpResponse<String> initializeOverRawHttp(String token) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
						"capabilities":{},"clientInfo":{"name":"raw-test","version":"1"}}}"""));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		try {
			return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (java.io.IOException | InterruptedException ex) {
			throw new IllegalStateException(ex);
		}
	}
}

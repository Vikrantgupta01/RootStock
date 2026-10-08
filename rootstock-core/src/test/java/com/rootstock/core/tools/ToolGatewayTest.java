package com.rootstock.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.tools.ToolCallResult.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The gateway's three outcomes the design names: allowed, blocked and failing calls. */
class ToolGatewayTest {

	private final List<String> invoked = new ArrayList<>();

	private final ToolCatalog catalog = new ToolCatalog(
			Map.of("find_household", new ToolDefinition("find_household", "client", "find_household", ToolAccess.READ),
					"lookup", new ToolDefinition("lookup", "client", "remote_lookup", ToolAccess.READ),
					"create_case", new ToolDefinition("create_case", "client", "create_case", ToolAccess.WRITE),
					"broken", new ToolDefinition("broken", "client", "broken", ToolAccess.READ),
					"down", new ToolDefinition("down", "client", "down", ToolAccess.READ)),
			Map.of("enrich", List.of("find_household", "lookup", "broken", "down"),
					"commit", List.of("create_case")),
			Set.of("commit"));

	/** Stands in for MCP: records what it was asked to run. */
	private final ToolInvoker invoker = (tool, arguments, context) -> {
		invoked.add(tool.remoteName());
		return switch (tool.name()) {
			case "broken" -> throw new ToolInvoker.ToolErrorException("No household with ref HH-9999");
			case "down" -> throw new IllegalStateException("send failed",
					new java.net.ConnectException("Connection refused"));
			default -> "{\"tool\":\"" + tool.remoteName() + "\",\"args\":" + arguments.size() + "}";
		};
	};

	private final ToolGateway gateway = new ToolGateway(catalog, invoker);

	@Test
	void anAllowedCallRunsAndReturnsTheOutput() {
		ToolCallResult result = gateway.call("enrich", "find_household", Map.of("name", "Linh Tran"),
				new ToolCallContext("case-1", "volunteer-1"));

		assertThat(result.status()).isEqualTo(Status.OK);
		assertThat(result.output()).contains("find_household");
		assertThat(result.connection()).isEqualTo("client");
		assertThat(invoked).containsExactly("find_household");
	}

	@Test
	void aLogicalNameIsCalledByItsRemoteName() {
		gateway.call("enrich", "lookup", Map.of(), ToolCallContext.NONE);

		assertThat(invoked).containsExactly("remote_lookup");
	}

	@Test
	void aToolNotOnTheNodesAllowlistIsBlockedAndNeverSent() {
		ToolCallResult result = gateway.call("enrich", "create_case", Map.of(), ToolCallContext.NONE);

		assertThat(result.status()).isEqualTo(Status.BLOCKED);
		assertThat(result.message()).contains("'create_case'", "'enrich'", "find_household");
		assertThat(invoked).isEmpty();
	}

	@Test
	void anUnknownNodeMayCallNothing() {
		assertThat(gateway.call("draft", "find_household", Map.of(), ToolCallContext.NONE).status())
				.isEqualTo(Status.BLOCKED);
		assertThat(invoked).isEmpty();
	}

	@Test
	void anUnknownToolIsReportedNotSent() {
		ToolCallResult result = gateway.call("enrich", "delete_everything", Map.of(), ToolCallContext.NONE);

		assertThat(result.status()).isEqualTo(Status.UNKNOWN_TOOL);
		assertThat(invoked).isEmpty();
	}

	@Test
	void aClientSystemErrorIsAToolErrorWithItsMessage() {
		ToolCallResult result = gateway.call("enrich", "broken", Map.of(), ToolCallContext.NONE);

		assertThat(result.status()).isEqualTo(Status.TOOL_ERROR);
		assertThat(result.message()).isEqualTo("No household with ref HH-9999");
		assertThat(result.output()).isNull();
	}

	@Test
	void anUnreachableSystemIsUnavailableWithTheRootCause() {
		ToolCallResult result = gateway.call("enrich", "down", Map.of(), ToolCallContext.NONE);

		assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
		assertThat(result.message()).contains("'client'", "Connection refused");
	}

	@Test
	void aWriteToolRunsInItsWriteNode() {
		assertThat(gateway.call("commit", "create_case", Map.of(), ToolCallContext.NONE).status())
				.isEqualTo(Status.OK);
	}

	@Test
	void missingArgumentsAndContextAreSafe() {
		assertThat(gateway.call("enrich", "find_household", null, null).ok()).isTrue();
	}
}

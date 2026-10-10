package com.rootstock.runtime.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.UserRole;
import com.rootstock.runtime.common.GlobalExceptionHandler;
import com.rootstock.core.tools.ToolAccess;
import com.rootstock.core.tools.ToolCallContext;
import com.rootstock.core.tools.ToolCallResult;
import com.rootstock.core.tools.ToolCallResult.Status;
import com.rootstock.core.tools.ToolGateway;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Security is out of this slice, as in the other controller tests; the admin-only
// rule (@PreAuthorize) is checked against the running app.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ToolExplorerController.class)
@Import(GlobalExceptionHandler.class)
class ToolExplorerControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ToolGateway gateway;

	@MockitoBean
	ToolExplorerService explorer;

	@BeforeEach
	void signIn() {
		AuthContext.set(new AuthContext.Principal("cognito-sub-1", "admin@example.com", UserRole.ADMIN, Set.of()));
	}

	@AfterEach
	void signOut() {
		AuthContext.clear();
	}

	@Test
	void listsNodesAndToolsAsTheClientSystemDescribesThem() throws Exception {
		given(explorer.overview()).willReturn(new ToolExplorerService.Overview(
				List.of(new ToolExplorerService.Node("enrich", List.of("find_household"))),
				List.of(new ToolExplorerService.Tool("find_household", "client", ToolAccess.READ, true,
						"Find a household", Map.of("type", "object"), null))));

		mockMvc.perform(get("/api/tools"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nodes[0].name").value("enrich"))
				.andExpect(jsonPath("$.nodes[0].allowed[0]").value("find_household"))
				.andExpect(jsonPath("$.tools[0].available").value(true))
				.andExpect(jsonPath("$.tools[0].description").value("Find a household"));
	}

	@Test
	void runsTheCallAsTheSignedInUserNotWhateverTheRequestClaims() throws Exception {
		given(gateway.call(eq("enrich"), eq("find_household"), anyMap(), any())).willReturn(
				new ToolCallResult(Status.OK, "enrich", "find_household", "client", "{\"matches\":[]}", null, 12));
		given(explorer.currentTraceId()).willReturn("abc123");
		given(explorer.traceUrl("abc123")).willReturn("https://langfuse.example/project/p/traces/abc123");

		mockMvc.perform(post("/api/tools/call").contentType(MediaType.APPLICATION_JSON).content("""
						{"node":"enrich","tool":"find_household","arguments":{"name":"Linh Tran"},
						 "caseId":"case-42","actingUser":"someone-else"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.status").value("OK"))
				.andExpect(jsonPath("$.result.output").value("{\"matches\":[]}"))
				.andExpect(jsonPath("$.traceId").value("abc123"))
				.andExpect(jsonPath("$.traceUrl").value("https://langfuse.example/project/p/traces/abc123"));

		ArgumentCaptor<ToolCallContext> context = ArgumentCaptor.forClass(ToolCallContext.class);
		Mockito.verify(gateway).call(eq("enrich"), eq("find_household"), eq(Map.of("name", "Linh Tran")),
				context.capture());
		assertThat(context.getValue()).isEqualTo(new ToolCallContext("case-42", "cognito-sub-1"));
	}

	@Test
	void aBlockedCallIsAnOrdinaryAnswerNotAnHttpError() throws Exception {
		given(gateway.call(eq("enrich"), eq("ping"), any(), any())).willReturn(new ToolCallResult(
				Status.BLOCKED, "enrich", "ping", "client", null, "Tool 'ping' is not allowed in node 'enrich'.", 0));

		mockMvc.perform(post("/api/tools/call").contentType(MediaType.APPLICATION_JSON)
						.content("{\"node\":\"enrich\",\"tool\":\"ping\",\"caseId\":\"  \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.status").value("BLOCKED"))
				.andExpect(jsonPath("$.result.message").value("Tool 'ping' is not allowed in node 'enrich'."));

		ArgumentCaptor<ToolCallContext> context = ArgumentCaptor.forClass(ToolCallContext.class);
		Mockito.verify(gateway).call(eq("enrich"), eq("ping"), any(), context.capture());
		assertThat(context.getValue().caseId()).isNull();   // a blank case id is no case id
	}

	@Test
	void rejectsACallWithoutANodeOrTool() throws Exception {
		mockMvc.perform(post("/api/tools/call").contentType(MediaType.APPLICATION_JSON)
						.content("{\"node\":\"\",\"tool\":\"find_household\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(gateway);
	}
}

package com.rootstock.runtime.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.core.auth.AuthContext;
import com.rootstock.core.auth.UserRole;
import com.rootstock.core.cases.CaseGraphs;
import com.rootstock.core.cases.CaseRun;
import com.rootstock.core.cases.CaseRunService;
import com.rootstock.core.cases.RunRegistry;
import com.rootstock.core.graph.GraphCompiler;
import com.rootstock.core.graph.RepairsPack;
import com.rootstock.runtime.common.GlobalExceptionHandler;
import com.rootstock.runtime.observability.LangfuseLinks;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// Security filters are out of this slice, as in the other controller tests; the
// signed-in user comes from AuthContext, which those filters set in the app.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(CaseController.class)
@Import({ GlobalExceptionHandler.class, CaseControllerTest.Cases.class })
class CaseControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	RunRegistry runs;

	@MockitoBean
	LangfuseLinks langfuse;

	/** A real run service over the made-up repairs pack, with stubs that take no time. */
	@TestConfiguration
	static class Cases {

		@Bean
		RunRegistry runRegistry() {
			return new RunRegistry(List.of());
		}

		@Bean
		CaseGraphs caseGraphs(RunRegistry runs) {
			return new CaseGraphs(List.of(new GraphCompiler(RepairsPack.registry(), List.of(), runs)
					.compile(RepairsPack.load(), RepairsPack.ontology())));
		}

		@Bean(destroyMethod = "close")
		CaseRunService caseRunService(CaseGraphs graphs, RunRegistry runs) {
			return new CaseRunService(graphs, runs);
		}
	}

	private static void signIn(String user, UserRole role) {
		AuthContext.set(new AuthContext.Principal(user, user + "@example.com", role, Set.of()));
	}

	@AfterEach
	void signOut() {
		AuthContext.clear();
	}

	private String submit(String body) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andReturn();
		String caseId = JsonPath.read(result.getResponse().getContentAsString(), "$.caseId");
		CaseRun run = runs.byCase(caseId).orElseThrow();
		for (int i = 0; i < 200 && run.status() == CaseRun.Status.RUNNING; i++) {
			Thread.sleep(25);
		}
		return caseId;
	}

	@Test
	void aSubmittedCaseRunsToReviewAndItsDetailShowsWhatHappened() throws Exception {
		signIn("member-1", UserRole.VIEWER);
		String caseId = submit("{\"input\":\"Kitchen tap leaking\"}");

		mockMvc.perform(get("/api/cases/" + caseId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PAUSED"))
				.andExpect(jsonPath("$.pause.node").value("review"))
				.andExpect(jsonPath("$.pause.before").value(true))
				.andExpect(jsonPath("$.events[0].type").value("RUN_STARTED"))
				.andExpect(jsonPath("$.result.actions[0].type").value("VISIT"))
				.andExpect(jsonPath("$.result.rawInput").doesNotExist());
	}

	@Test
	void simulateDrivesTheStubsDownTheClarifyRoute() throws Exception {
		signIn("member-1", UserRole.VIEWER);
		String caseId = submit("{\"input\":\"Tap leaking\",\"simulate\":\"clarify\"}");

		mockMvc.perform(get("/api/cases/" + caseId))
				.andExpect(jsonPath("$.pause.node").value("clarify"))
				.andExpect(jsonPath("$.pause.before").value(false));
	}

	@Test
	void theEventStreamReplaysTheRunAndCloses() throws Exception {
		signIn("member-1", UserRole.VIEWER);
		String caseId = submit("{\"input\":\"Tap leaking\"}");

		MvcResult started = mockMvc.perform(get("/api/cases/" + caseId + "/events"))
				.andExpect(request().asyncStarted()).andReturn();
		String stream = mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();

		assertThat(stream).contains("event:run", "\"type\":\"RUN_STARTED\"", "\"node\":\"validate\"",
				"\"type\":\"RUN_PAUSED\"");
	}

	@Test
	void aCaseIsVisibleToItsSubmitterAndAdminsOnly() throws Exception {
		signIn("member-1", UserRole.VIEWER);
		String caseId = submit("{\"input\":\"Tap leaking\"}");

		signIn("member-2", UserRole.VIEWER);
		mockMvc.perform(get("/api/cases/" + caseId)).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/cases")).andExpect(jsonPath("$[?(@.caseId == '" + caseId + "')]").isEmpty());

		signIn("admin-1", UserRole.ADMIN);
		mockMvc.perform(get("/api/cases/" + caseId)).andExpect(status().isOk());
	}

	@Test
	void blankInputOrAnUnknownSimulationIsRejected() throws Exception {
		signIn("member-1", UserRole.VIEWER);

		mockMvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"  \"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
				.content("{\"input\":\"x\",\"simulate\":\"explode\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theGraphIsDescribedForDrawing() throws Exception {
		signIn("member-1", UserRole.VIEWER);

		mockMvc.perform(get("/api/cases/graph"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("job-intake"))
				.andExpect(jsonPath("$.nodes[1].id").value("extract"))
				.andExpect(jsonPath("$.nodes[1].kind").value("structured-extraction"))
				.andExpect(jsonPath("$.nodes[1].agent").value("job-extractor"))
				.andExpect(jsonPath("$.edges[4].branches[0].to").value("clarify"))
				.andExpect(jsonPath("$.edges[4].branches[2].label").value("otherwise"))
				.andExpect(jsonPath("$.interruptBefore[0]").value("review"));
	}
}

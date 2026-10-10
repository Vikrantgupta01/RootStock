package com.rootstock.runtime.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

// Security is deliberately out of this slice: the filter chain lives in
// SecurityConfig (not loaded by @WebMvcTest) and @PreAuthorize sits on the
// services this test mocks out. Authentication and role enforcement are
// covered end to end by the RAG integration tests instead.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(HealthController.class)
class HealthControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void reportsUp() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.app").value("RootStock"));
	}
}

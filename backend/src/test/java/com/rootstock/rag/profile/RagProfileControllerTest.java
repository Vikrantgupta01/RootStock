package com.rootstock.rag.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootstock.common.GlobalExceptionHandler;
import com.rootstock.common.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Security is deliberately out of this slice: the filter chain lives in
// SecurityConfig (not loaded by @WebMvcTest) and @PreAuthorize sits on the
// services this test mocks out. Authentication and role enforcement are
// covered end to end by the RAG integration tests instead.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RagProfileController.class)
@Import(GlobalExceptionHandler.class)
class RagProfileControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	RagProfileService service;

	private static RagProfile profile(String name, int versionNo, boolean active) {
		return new RagProfile.Builder()
				.tenantId("acme").name(name).versionNo(versionNo).active(active)
				.topK(4).similarityThreshold(0.5).rerankerEnabled(false)
				.maxContextTokens(4000).promptTemplate("{context}\n{question}")
				.build();
	}

	@Test
	void createReturns201() throws Exception {
		given(service.create(any())).willReturn(profile("accurate", 1, false));

		mockMvc.perform(post("/api/rag/profiles")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"accurate\",\"topK\":8}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("accurate"))
				.andExpect(jsonPath("$.versionNo").value(1))
				.andExpect(jsonPath("$.active").value(false));
	}

	@Test
	void createRejectsBlankName() throws Exception {
		mockMvc.perform(post("/api/rag/profiles")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void listReturnsOnePerName() throws Exception {
		given(service.listProfiles()).willReturn(List.of(profile("default", 1, true), profile("accurate", 3, false)));

		mockMvc.perform(get("/api/rag/profiles"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("default"));
	}

	@Test
	void getUnknownReturns404() throws Exception {
		UUID id = UUID.randomUUID();
		given(service.get(eq(id))).willThrow(ResourceNotFoundException.of("RAG profile", id));

		mockMvc.perform(get("/api/rag/profiles/{id}", id)).andExpect(status().isNotFound());
	}

	@Test
	void activateReturnsTheNowActiveProfile() throws Exception {
		UUID id = UUID.randomUUID();
		given(service.activate(eq(id))).willReturn(profile("accurate", 2, true));

		mockMvc.perform(post("/api/rag/profiles/{id}/activate", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("accurate"))
				.andExpect(jsonPath("$.active").value(true));
	}
}

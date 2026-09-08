package com.rootstock.rag.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootstock.chat.AiUnavailableException;
import com.rootstock.common.GlobalExceptionHandler;
import com.rootstock.rag.query.dto.Citation;
import com.rootstock.rag.query.dto.RagQueryResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RagQueryController.class)
@Import(GlobalExceptionHandler.class)
class RagQueryControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	RagQueryService service;

	@Test
	void returnsAnswerWithCitations() throws Exception {
		UUID docId = UUID.randomUUID();
		RagQueryResponse response = new RagQueryResponse(
				"Refunds are available within 14 days [1].", true,
				List.of(new Citation(1, docId, "policy.txt", "policy.txt", 1, 0, 0.82, "refunds within 14 days")),
				UUID.randomUUID(), "default", 1, List.of(UUID.randomUUID()));
		given(service.query(any())).willReturn(response);

		mockMvc.perform(post("/api/rag/query")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"refund policy?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.grounded").value(true))
				.andExpect(jsonPath("$.citations[0].sourceKey").value("policy.txt"))
				.andExpect(jsonPath("$.citations[0].rank").value(1));
	}

	@Test
	void blankQuestionIsRejected() throws Exception {
		mockMvc.perform(post("/api/rag/query")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"  \"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aiUnavailableReturns503() throws Exception {
		given(service.query(any())).willThrow(new AiUnavailableException("no backend"));

		mockMvc.perform(post("/api/rag/query")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"anything\"}"))
				.andExpect(status().isServiceUnavailable());
	}
}

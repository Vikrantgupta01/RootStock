package com.rootstock.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rootstock.agent.dto.AgentStep;
import com.rootstock.chat.AiUnavailableException;
import com.rootstock.common.GlobalExceptionHandler;
import com.rootstock.conversation.Conversation;
import com.rootstock.conversation.ConversationKind;
import com.rootstock.conversation.ConversationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Security is out of this slice, as in ChatControllerTest.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(AgentController.class)
@Import(GlobalExceptionHandler.class)
class AgentControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	AgentService agent;

	@MockitoBean
	ConversationService conversations;

	private final Conversation conversation = new Conversation("t", "u", ConversationKind.AGENT, "hello");

	@BeforeEach
	void stubConversation() {
		given(conversations.resolve(any(), eq(ConversationKind.AGENT), any())).willReturn(conversation);
		given(conversations.history(any())).willReturn(List.of());
	}

	@Test
	void returnsAnswerWithSteps() throws Exception {
		given(agent.run(any(), eq("hello"))).willReturn(new AgentService.Result("hi there", 2,
				List.of(new AgentStep(1, "Checking the date.", "currentDateTime", "{}", "2026-10-05T10:00Z"))));

		mockMvc.perform(post("/api/agent")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"hello\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value("hi there"))
				.andExpect(jsonPath("$.iterations").value(2))
				.andExpect(jsonPath("$.steps[0].tool").value("currentDateTime"))
				.andExpect(jsonPath("$.steps[0].thought").value("Checking the date."))
				.andExpect(jsonPath("$.steps[0].observation").value("2026-10-05T10:00Z"));

		// History keeps the question and the final answer, not the tool traffic.
		verify(conversations).append(conversation, "hello", "hi there");
	}

	@Test
	void rejectsBlankMessage() throws Exception {
		mockMvc.perform(post("/api/agent")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returns503WhenAiUnavailable() throws Exception {
		given(agent.run(any(), eq("hello"))).willThrow(new AiUnavailableException("no backend"));

		mockMvc.perform(post("/api/agent")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"hello\"}"))
				.andExpect(status().isServiceUnavailable());
	}
}

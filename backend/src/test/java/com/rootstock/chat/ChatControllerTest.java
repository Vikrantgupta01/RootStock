package com.rootstock.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

// Security is deliberately out of this slice: the filter chain lives in
// SecurityConfig (not loaded by @WebMvcTest) and @PreAuthorize sits on the
// services this test mocks out. Authentication and role enforcement are
// covered end to end by the RAG integration tests instead.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ChatController.class)
@Import(GlobalExceptionHandler.class)
class ChatControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ChatService chatService;

	@MockitoBean
	ConversationService conversations;

	@BeforeEach
	void stubConversation() {
		given(conversations.resolve(any(), any(), any()))
				.willReturn(new Conversation("t", "u", ConversationKind.CHAT, "hello"));
		given(conversations.history(any())).willReturn(List.of());
	}

	@Test
	void returnsReplyForValidRequest() throws Exception {
		given(chatService.reply(any(), eq("hello"))).willReturn("hi there");

		mockMvc.perform(post("/api/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"hello\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reply").value("hi there"));
	}

	@Test
	void rejectsBlankMessage() throws Exception {
		mockMvc.perform(post("/api/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returns503WhenAiUnavailable() throws Exception {
		given(chatService.reply(any(), eq("hello")))
				.willThrow(new AiUnavailableException("no backend"));

		mockMvc.perform(post("/api/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"hello\"}"))
				.andExpect(status().isServiceUnavailable());
	}
}

package com.rootstock.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.rootstock.TestcontainersConfiguration;
import com.rootstock.auth.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

/**
 * Conversation memory end to end for {@code /api/chat}: a second message in the
 * same thread must reach the model with the first exchange in front of it, and a
 * thread must belong to exactly one person.
 *
 * <p>The stub {@link ChatModel} records every prompt it is handed, which is the
 * only way to tell "the history was loaded" from "the history was loaded and
 * then not sent" -- the reply alone would look the same either way.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, ConversationIntegrationTest.RecordingChatModelConfig.class})
class ConversationIntegrationTest {

	static final List<List<Message>> PROMPTS = new ArrayList<>();

	@TestConfiguration(proxyBeanMethods = false)
	static class RecordingChatModelConfig {
		@Bean
		ChatModel recordingChatModel() {
			return prompt -> {
				PROMPTS.add(List.copyOf(prompt.getInstructions()));
				return new ChatResponse(List.of(new Generation(new AssistantMessage("ack"))));
			};
		}
	}

	@Autowired
	MockMvc mockMvc;

	@BeforeAll
	static void requireDocker() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Docker is not available - skipping conversation integration test");
	}

	@Test
	void aSecondMessageCarriesTheFirstExchange() throws Exception {
		String tenant = tenant();
		PROMPTS.clear();

		String first = send(tenant, null, "My favourite colour is teal.");
		UUID conversationId = UUID.fromString(JsonPath.read(first, "$.conversationId"));

		// A brand new thread has nothing to replay: just the message itself.
		assertThat(texts(PROMPTS.get(0))).containsExactly("My favourite colour is teal.");

		send(tenant, conversationId, "What is it?");
		// The follow-up arrives behind the whole prior exchange, in order.
		assertThat(texts(PROMPTS.get(1)))
				.containsExactly("My favourite colour is teal.", "ack", "What is it?");

		// Omitting the id starts a fresh thread rather than continuing this one.
		String separate = send(tenant, null, "Unrelated question.");
		assertThat(UUID.fromString(JsonPath.read(separate, "$.conversationId"))).isNotEqualTo(conversationId);
		assertThat(texts(PROMPTS.get(2))).containsExactly("Unrelated question.");

		// The transcript is the durable record, not the browser's in-memory log.
		mockMvc.perform(get("/api/chat/conversations/{id}", conversationId).with(TestTokens.admin(tenant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[0].role").value("USER"))
				.andExpect(jsonPath("$[0].content").value("My favourite colour is teal."))
				.andExpect(jsonPath("$[1].role").value("ASSISTANT"))
				.andExpect(jsonPath("$[3].content").value("ack"));

		mockMvc.perform(get("/api/chat/conversations").with(TestTokens.admin(tenant)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				// Newest activity first, and titled from the opening message.
				.andExpect(jsonPath("$[0].title").value("Unrelated question."));

		mockMvc.perform(delete("/api/chat/conversations/{id}", conversationId).with(TestTokens.admin(tenant)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/chat/conversations/{id}", conversationId).with(TestTokens.admin(tenant)))
				.andExpect(status().isNotFound());
	}

	@Test
	void aConversationBelongsToOnePersonOnly() throws Exception {
		String tenant = tenant();
		PROMPTS.clear();

		String first = send(tenant, null, "Something private.");
		UUID conversationId = UUID.fromString(JsonPath.read(first, "$.conversationId"));

		// Same tenant, different person: knowing the id gets you nothing. Chat has
		// no sharing model, unlike documents.
		mockMvc.perform(get("/api/chat/conversations/{id}", conversationId).with(TestTokens.viewer(tenant)))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/chat")
						.with(TestTokens.viewer(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"continue it\",\"conversationId\":\"" + conversationId + "\"}"))
				.andExpect(status().isNotFound());

		// And a RAG thread can't be continued as a plain chat one: they answer
		// under different prompts and different grounding rules.
		String ragged = mockMvc.perform(post("/api/rag/query")
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"anything?\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		UUID ragConversationId = UUID.fromString(JsonPath.read(ragged, "$.conversationId"));
		mockMvc.perform(post("/api/chat")
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"message\":\"hi\",\"conversationId\":\"" + ragConversationId + "\"}"))
				.andExpect(status().isBadRequest());
	}

	private String send(String tenant, UUID conversationId, String message) throws Exception {
		String body = conversationId == null
				? "{\"message\":\"" + message + "\"}"
				: "{\"message\":\"" + message + "\",\"conversationId\":\"" + conversationId + "\"}";
		return mockMvc.perform(post("/api/chat")
						.with(TestTokens.admin(tenant))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	/** Prompt contents minus the system message, which isn't conversation history. */
	private static List<String> texts(List<Message> instructions) {
		return instructions.stream()
				.filter(m -> !(m instanceof SystemMessage))
				.map(Message::getText)
				.toList();
	}

	private static String tenant() {
		return "conv-" + UUID.randomUUID().toString().substring(0, 8);
	}
}

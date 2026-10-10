package com.rootstock.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.rootstock.core.chat.ChatService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * One real round trip to the configured Bedrock chat model. Tagged {@code live}:
 * it costs a fraction of a cent, so it runs only on demand
 * ({@code mvn verify -Dlive.excluded=none -Dgroups=live}), never in a normal build.
 */
@Tag("live")
@SpringBootTest
@Import(ThrowawaySchemaConfig.class)
class BedrockChatLiveIT {

	@Autowired
	ChatService chat;

	@Test
	void theConfiguredModelAnswers() {
		String answer = chat.generate("You answer with exactly one word.", "Reply with the word READY and nothing else.");

		assertThat(answer).containsIgnoringCase("ready");
	}
}

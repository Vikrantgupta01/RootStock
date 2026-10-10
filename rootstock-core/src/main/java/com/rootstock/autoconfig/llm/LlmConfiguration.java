package com.rootstock.autoconfig.llm;

import com.rootstock.autoconfig.observability.LangfuseProperties;
import com.rootstock.core.llm.BundledPrompts;
import com.rootstock.core.llm.LangfusePromptSource;
import com.rootstock.core.llm.LlmService;
import com.rootstock.core.llm.ModelProfile;
import com.rootstock.core.llm.PromptRegistry;
import com.rootstock.core.llm.PromptTemplate;
import com.rootstock.core.pack.LoadedPack;
import com.rootstock.core.pack.PackRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The model profiles case agents call through, and where their prompts come
 * from: Langfuse when its keys are set, otherwise (and as a fallback) the copies
 * bundled in each pack's {@code prompts/} folder.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

	private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

	@Bean(destroyMethod = "close")
	LlmService llmService(ObjectProvider<ChatModel> models, LlmProperties properties) {
		Map<String, ModelProfile> profiles = new LinkedHashMap<>();
		properties.profiles().forEach((name, p) -> profiles.put(name, new ModelProfile(
				p.model() == null || p.model().isBlank() ? null : p.model().strip(), p.temperature(), p.maxTokens())));
		log.info("Model profiles: {}", profiles.entrySet().stream()
				.map(e -> e.getKey() + "=" + (e.getValue().model() == null ? "default model" : e.getValue().model()))
				.toList());
		return new LlmService(models, profiles);
	}

	@Bean
	PromptRegistry promptRegistry(LlmProperties properties, LangfuseProperties langfuse, PackRegistry packs) {
		LangfusePromptSource remote = langfuse.isUsable() ? new LangfusePromptSource(langfuse.host(),
				langfuse.publicKey(), langfuse.secretKey(), properties.prompts().fetchTimeout()) : null;
		if (remote == null) {
			log.info("Langfuse is not configured: prompts come only from the packs' {}/ folders", BundledPrompts.DIR);
		}
		PromptRegistry registry = new PromptRegistry(remote, properties.prompts().cacheTtl(), Clock.systemUTC());
		for (LoadedPack pack : packs.snapshot().packs()) {
			Map<String, PromptTemplate> bundled = BundledPrompts.load(Path.of(pack.location()));
			if (!bundled.isEmpty()) {
				log.info("Pack '{}' bundles prompts {}", pack.name(), bundled.keySet());
			}
			registry.addBundled(bundled);
		}
		return registry;
	}
}

package com.rootstock.autoconfig.pack;

import com.rootstock.core.ontology.Ontology;
import com.rootstock.core.ontology.OntologyLoader;
import com.rootstock.core.ontology.OntologyValidator;
import com.rootstock.core.pack.LoadedPack;
import com.rootstock.core.pack.PackRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the core ontology (a broken one is a Rootstock bug, so startup stops)
 * and the domain packs from {@code rootstock.packs.paths} (a broken pack is
 * logged and listed, not fatal).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PacksProperties.class)
public class PackConfiguration {

	static final String CORE_ONTOLOGY = "ontology/rootstock-core.yaml";

	private static final Logger log = LoggerFactory.getLogger(PackConfiguration.class);

	@Bean
	PackRegistry packRegistry(PacksProperties properties) {
		Ontology core = coreOntology();
		PackRegistry registry = new PackRegistry(core, properties.paths().stream().map(Path::of).toList());
		logPacks(registry.snapshot(), properties);
		return registry;
	}

	static Ontology coreOntology() {
		try (InputStream in = new ClassPathResource(CORE_ONTOLOGY).getInputStream()) {
			Ontology core = new OntologyLoader().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			var problems = new OntologyValidator().validateCore(core);
			if (!problems.isEmpty()) {
				throw new IllegalStateException("The core ontology is invalid: " + problems);
			}
			return core;
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + CORE_ONTOLOGY, e);
		}
	}

	static void logPacks(PackRegistry.Snapshot snapshot, PacksProperties properties) {
		if (properties.paths().isEmpty()) {
			log.info("No domain packs configured (set ROOTSTOCK_PACKS_PATHS)");
		}
		snapshot.pathProblems().forEach(p -> log.warn("Packs: {}", p));
		for (LoadedPack pack : snapshot.packs()) {
			switch (pack.status()) {
				case VALID -> log.info("Pack '{}' at {}: ontology {} {} valid ({} concepts, {} vocabularies, {} projections); files {}",
						pack.name(), pack.location(), pack.ontology().name(), pack.ontology().version(),
						pack.ontology().entities().size(), pack.ontology().vocabularies().size(),
						pack.ontology().projections().size(), pack.files());
				case NO_ONTOLOGY -> log.info("Pack '{}' at {}: no {}; files {}", pack.name(), pack.location(),
						LoadedPack.ONTOLOGY_FILE, pack.files());
				case INVALID -> log.warn("Pack '{}' at {}: ontology INVALID, {} problem(s):\n  {}", pack.name(),
						pack.location(), pack.problems().size(),
						String.join("\n  ", pack.problems().stream().map(Object::toString).toList()));
			}
		}
	}
}

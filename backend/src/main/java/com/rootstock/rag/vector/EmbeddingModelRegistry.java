package com.rootstock.rag.vector;

import com.rootstock.rag.RagProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel;
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Resolves a profile's {@code embeddingModelId} to a concrete
 * {@link EmbeddingModel} and its vector dimension. Built directly (not via Spring
 * AI auto-configuration) so several models can coexist.
 *
 * <ul>
 *   <li>{@code fake} — offline {@link FakeEmbeddingModel}; always available.</li>
 *   <li>{@code bedrock-titan-v2} — Amazon Titan Text Embeddings v2 (1024);
 *       registered only when {@code rootstock.rag.embedding.mode=bedrock}.</li>
 * </ul>
 */
@Component
public class EmbeddingModelRegistry {

	public static final String FAKE = "fake";
	public static final String BEDROCK_TITAN_V2 = "bedrock-titan-v2";

	private static final Map<String, Integer> DIMENSIONS = Map.of(
			FAKE, 1024,
			BEDROCK_TITAN_V2, 1024);

	private final Map<String, EmbeddingModel> models = new LinkedHashMap<>();

	public EmbeddingModelRegistry(RagProperties properties) {
		models.put(FAKE, new FakeEmbeddingModel(properties.embedding().fakeDimensions()));

		if ("bedrock".equalsIgnoreCase(properties.embedding().mode())) {
			TitanEmbeddingBedrockApi api = new TitanEmbeddingBedrockApi(
					"amazon.titan-embed-text-v2:0",
					properties.embedding().region(),
					Duration.ofMinutes(2));
			models.put(BEDROCK_TITAN_V2, new BedrockTitanEmbeddingModel(api, ObservationRegistry.NOOP));
		}
	}

	public Set<String> availableIds() {
		return models.keySet();
	}

	public EmbeddingModel get(String modelId) {
		EmbeddingModel model = models.get(modelId);
		if (model == null) {
			throw new IllegalArgumentException("Unknown or disabled embedding model: " + modelId
					+ " (available: " + models.keySet() + ")");
		}
		return model;
	}

	public int dimensionOf(String modelId) {
		Integer dim = DIMENSIONS.get(modelId);
		if (dim == null) {
			throw new IllegalArgumentException("Unknown embedding model: " + modelId);
		}
		return dim;
	}
}

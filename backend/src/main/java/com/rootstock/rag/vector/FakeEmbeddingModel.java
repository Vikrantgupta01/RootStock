package com.rootstock.rag.vector;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic, offline embedding model for local development and tests -- no
 * AWS calls, no cost. Uses hashed bag-of-words: embeddings of texts that share
 * vocabulary have high cosine similarity, so retrieval behaves plausibly without
 * a real model.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

	private final int dimensions;

	public FakeEmbeddingModel(int dimensions) {
		this.dimensions = dimensions;
	}

	@Override
	public int dimensions() {
		return dimensions;
	}

	@Override
	public float[] embed(Document document) {
		return vectorFor(document.getText());
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<Embedding> embeddings = new ArrayList<>();
		List<String> inputs = request.getInstructions();
		for (int i = 0; i < inputs.size(); i++) {
			embeddings.add(new Embedding(vectorFor(inputs.get(i)), i));
		}
		return new EmbeddingResponse(embeddings);
	}

	private float[] vectorFor(String text) {
		float[] vector = new float[dimensions];
		if (text != null) {
			for (String token : text.toLowerCase().split("\\W+")) {
				if (!token.isBlank()) {
					vector[Math.floorMod(token.hashCode(), dimensions)] += 1f;
				}
			}
		}
		double norm = 0;
		for (float v : vector) {
			norm += (double) v * v;
		}
		norm = Math.sqrt(norm);
		if (norm == 0) {
			vector[0] = 1f;
			return vector;
		}
		for (int i = 0; i < dimensions; i++) {
			vector[i] /= (float) norm;
		}
		return vector;
	}
}

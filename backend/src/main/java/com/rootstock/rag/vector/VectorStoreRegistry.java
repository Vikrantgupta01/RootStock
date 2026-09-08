package com.rootstock.rag.vector;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lazily builds one {@link PgVectorStore} per embedding model, each bound to the
 * physical table for that model's dimension ({@code vector_store_<dim>}, created
 * by Flyway). Schema init and table validation are disabled -- RootStock owns
 * the DDL.
 */
@Component
public class VectorStoreRegistry {

	private final JdbcTemplate jdbcTemplate;
	private final EmbeddingModelRegistry embeddingModels;
	private final ConcurrentHashMap<String, VectorStore> stores = new ConcurrentHashMap<>();

	public VectorStoreRegistry(JdbcTemplate jdbcTemplate, EmbeddingModelRegistry embeddingModels) {
		this.jdbcTemplate = jdbcTemplate;
		this.embeddingModels = embeddingModels;
	}

	public VectorStore forModel(String embeddingModelId) {
		return stores.computeIfAbsent(embeddingModelId, id -> {
			int dimension = embeddingModels.dimensionOf(id);
			return PgVectorStore.builder(jdbcTemplate, embeddingModels.get(id))
					.vectorTableName("vector_store_" + dimension)
					.dimensions(dimension)
					.distanceType(PgDistanceType.COSINE_DISTANCE)
					.indexType(PgIndexType.HNSW)
					.initializeSchema(false)
					.vectorTableValidationsEnabled(false)
					.build();
		});
	}
}

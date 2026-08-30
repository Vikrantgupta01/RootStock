-- RootStock initial schema.
--
-- Enables the pgvector extension so the database is ready for the Spring AI
-- VectorStore. The `vector_store` table itself is created by Spring AI's
-- pgvector auto-configuration (spring.ai.vectorstore.pgvector.initialize-schema=true)
-- once an EmbeddingModel is enabled -- see README.
CREATE EXTENSION IF NOT EXISTS vector;

-- Placeholder table so `ddl-auto: validate` has something to validate and the
-- first real feature has a migration to follow. Drop or evolve as the domain
-- takes shape.
CREATE TABLE app_info (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key         VARCHAR(128) NOT NULL UNIQUE,
    value       VARCHAR(512) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO app_info (key, value) VALUES ('schema.version', '1');

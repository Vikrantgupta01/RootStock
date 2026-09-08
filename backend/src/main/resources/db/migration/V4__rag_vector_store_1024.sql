-- Vector store for 1024-dimension embeddings (AWS Bedrock Titan Text Embeddings v2,
-- and the local fake embedding model). pgvector columns are fixed-width, so each
-- embedding dimension gets its own table; add vector_store_1536 etc. later the
-- same way. Managed by RootStock (not Spring AI's schema initializer).

CREATE TABLE vector_store_1024 (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content    TEXT NOT NULL,
    metadata   JSONB NOT NULL DEFAULT '{}',
    embedding  VECTOR(1024) NOT NULL
);

CREATE INDEX ix_vs1024_embedding ON vector_store_1024
    USING hnsw (embedding vector_cosine_ops);

CREATE INDEX ix_vs1024_metadata ON vector_store_1024
    USING gin (metadata jsonb_path_ops);

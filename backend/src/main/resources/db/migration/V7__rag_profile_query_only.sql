-- rag_profile no longer owns chunking/embedding config or hybrid search --
-- the Bedrock Knowledge Base owns ingestion, chunking, and embedding now.
-- Profiles are reduced to query-time knobs only.

ALTER TABLE rag_profile
    DROP COLUMN chunking_strategy,
    DROP COLUMN chunk_size,
    DROP COLUMN chunk_overlap,
    DROP COLUMN embedding_model_id,
    DROP COLUMN hybrid_search;

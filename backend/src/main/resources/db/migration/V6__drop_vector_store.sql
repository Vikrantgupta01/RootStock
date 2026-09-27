-- Vector storage and blue/green re-index tracking move to AWS Bedrock Knowledge
-- Bases + Aurora PostgreSQL Serverless v2, outside this database. See the RAG
-- migration plan for the replacement architecture.

DROP TABLE vector_store_1024;
DROP TABLE rag_profile_activation;

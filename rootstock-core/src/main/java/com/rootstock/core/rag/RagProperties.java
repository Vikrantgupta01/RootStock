package com.rootstock.core.rag;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code rootstock.rag.*} namespace: blob storage, the Bedrock
 * Knowledge Base, the ingestion poller, and the seed values for a tenant's
 * default RAG profile. (Tenant identity is no longer configured here -- it comes
 * off the caller's verified token; see {@code com.rootstock.auth}.)
 */
@ConfigurationProperties(prefix = "rootstock.rag")
public record RagProperties(
		@DefaultValue Blob blob,
		@DefaultValue Bedrock bedrock,
		@DefaultValue Ingest ingest,
		@DefaultValue Defaults defaults) {

	public record Blob(
			@DefaultValue("filesystem") String backend,
			@DefaultValue Filesystem filesystem,
			@DefaultValue S3 s3,
			@DefaultValue("15m") Duration presignTtl) {

		public record Filesystem(@DefaultValue("./data/blobs") String dir) {
		}

		public record S3(
				@DefaultValue("rootstock-rag") String bucket,
				String endpoint,
				@DefaultValue("us-east-1") String region,
				@DefaultValue("true") boolean pathStyleAccess) {
		}
	}

	/** The Bedrock Knowledge Base (Aurora PostgreSQL Serverless v2 vector store) backing RAG. */
	public record Bedrock(
			String knowledgeBaseId,
			String dataSourceId,
			@DefaultValue("us-east-1") String region) {
	}

	public record Ingest(
			@DefaultValue("true") boolean pollerEnabled,
			@DefaultValue("5000") long pollIntervalMs,
			@DefaultValue("5") int batchSize,
			@DefaultValue("3") int maxAttempts) {
	}

	public record Defaults(
			@DefaultValue("4") int topK,
			@DefaultValue("0.5") double similarityThreshold,
			@DefaultValue("4000") int maxContextTokens,
			@DefaultValue(DEFAULT_PROMPT) String promptTemplate) {
	}

	static final String DEFAULT_PROMPT = """
			You are RootStock's knowledge assistant. Answer the question using only \
			the context passages below. If the answer is not in the context, say you \
			don't know. Cite passages by their source.

			Context:
			{context}

			Question: {question}
			""";
}

package com.rootstock.core.rag.vector;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.ConflictException;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJobSortByAttribute;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJobStatistics;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJobStatus;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJobSummary;
import software.amazon.awssdk.services.bedrockagent.model.SortOrder;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalFilter;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;
import software.amazon.awssdk.services.bedrockagentruntime.model.VectorSearchRerankingConfigurationType;

/**
 * Wraps the two Bedrock Knowledge Base API surfaces this app needs: the control
 * plane ({@link BedrockAgentClient}) to trigger and wait for a data-source sync,
 * and the data plane ({@link BedrockAgentRuntimeClient}) to retrieve chunks.
 *
 * <p>Bedrock re-syncs an entire data source per ingestion job -- there is no
 * per-document sync -- and disallows two concurrent jobs on the same data source
 * ({@link ConflictException}). {@link #sync} handles that by finding and waiting
 * on the in-flight job instead of starting a new one, and gives up after a bounded
 * poll window so the caller's own retry/backoff (the {@code ingestion_job} ledger)
 * is what carries a slow sync across multiple attempts.
 */
@Component
public class BedrockKnowledgeBaseClient {

	private static final int MAX_POLLS = 20;
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

	private final BedrockAgentClient agent;
	private final BedrockAgentRuntimeClient agentRuntime;

	public BedrockKnowledgeBaseClient(BedrockAgentClient agent, BedrockAgentRuntimeClient agentRuntime) {
		this.agent = agent;
		this.agentRuntime = agentRuntime;
	}

	/**
	 * Triggers a data-source sync and waits (bounded) for it to finish. Throws if
	 * the job fails, is stopped, or does not complete within the poll window --
	 * the caller's retry ledger is expected to call this again later. Returns the
	 * completed job's statistics so the caller can tell a sync that genuinely
	 * touched nothing (a misconfigured data source, or content that never reached
	 * S3) apart from one that actually processed a document.
	 */
	public IngestionJobStatistics sync(String knowledgeBaseId, String dataSourceId) {
		String jobId = startOrJoinInFlight(knowledgeBaseId, dataSourceId);
		return awaitCompletion(knowledgeBaseId, dataSourceId, jobId);
	}

	/**
	 * @param rerankerModelArn when non-null, re-scores the {@code numberOfResults}
	 *                         hits with this Bedrock reranking model (e.g.
	 *                         {@code arn:aws:bedrock:*::foundation-model/cohere.rerank-v3-5:0});
	 *                         null skips reranking entirely.
	 */
	public List<KnowledgeBaseRetrievalResult> retrieve(String knowledgeBaseId, String queryText, int numberOfResults,
			RetrievalFilter filter, String rerankerModelArn) {
		RetrieveResponse response = agentRuntime.retrieve(r -> r
				.knowledgeBaseId(knowledgeBaseId)
				.retrievalQuery(q -> q.text(queryText))
				.retrievalConfiguration(rc -> rc.vectorSearchConfiguration(vs -> {
					vs.numberOfResults(numberOfResults).filter(filter);
					if (rerankerModelArn != null) {
						vs.rerankingConfiguration(rk -> rk
								.type(VectorSearchRerankingConfigurationType.BEDROCK_RERANKING_MODEL)
								.bedrockRerankingConfiguration(brc -> brc
										.modelConfiguration(mc -> mc.modelArn(rerankerModelArn))));
					}
				})));
		return response.retrievalResults();
	}

	private String startOrJoinInFlight(String knowledgeBaseId, String dataSourceId) {
		try {
			var response = agent.startIngestionJob(r -> r.knowledgeBaseId(knowledgeBaseId).dataSourceId(dataSourceId));
			return response.ingestionJob().ingestionJobId();
		}
		catch (ConflictException alreadyRunning) {
			return findInFlightJobId(knowledgeBaseId, dataSourceId)
					.orElseThrow(() -> new IllegalStateException(
							"Bedrock reported a conflicting ingestion job on data source " + dataSourceId
									+ " but none is currently in flight", alreadyRunning));
		}
	}

	private Optional<String> findInFlightJobId(String knowledgeBaseId, String dataSourceId) {
		var response = agent.listIngestionJobs(r -> r
				.knowledgeBaseId(knowledgeBaseId)
				.dataSourceId(dataSourceId)
				.sortBy(s -> s.attribute(IngestionJobSortByAttribute.STARTED_AT).order(SortOrder.DESCENDING))
				.maxResults(10));
		return response.ingestionJobSummaries().stream()
				.filter(BedrockKnowledgeBaseClient::inFlight)
				.map(IngestionJobSummary::ingestionJobId)
				.findFirst();
	}

	private static boolean inFlight(IngestionJobSummary summary) {
		return summary.status() == IngestionJobStatus.STARTING || summary.status() == IngestionJobStatus.IN_PROGRESS;
	}

	private IngestionJobStatistics awaitCompletion(String knowledgeBaseId, String dataSourceId,
			String ingestionJobId) {
		for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
			var job = agent.getIngestionJob(r -> r
							.knowledgeBaseId(knowledgeBaseId).dataSourceId(dataSourceId).ingestionJobId(ingestionJobId))
					.ingestionJob();
			if (job.status() == IngestionJobStatus.COMPLETE) {
				return job.statistics();
			}
			if (job.status() == IngestionJobStatus.FAILED || job.status() == IngestionJobStatus.STOPPED) {
				throw new IllegalStateException(
						"Bedrock ingestion job " + ingestionJobId + " on data source " + dataSourceId
								+ " ended with status " + job.status());
			}
			sleep();
		}
		throw new IllegalStateException("Bedrock ingestion job " + ingestionJobId + " on data source " + dataSourceId
				+ " did not complete within " + (MAX_POLLS * POLL_INTERVAL.toSeconds()) + "s; will retry later");
	}

	private static void sleep() {
		try {
			Thread.sleep(POLL_INTERVAL.toMillis());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting on a Bedrock ingestion job", e);
		}
	}
}

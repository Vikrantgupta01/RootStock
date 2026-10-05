package com.rootstock.rag.ingest;

import com.rootstock.rag.RagConfig;
import com.rootstock.rag.RagProperties;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically claims queued ingestion jobs and hands each to the bounded
 * ingestion executor. Safe to run on multiple instances -- {@code claim} uses
 * {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
@ConditionalOnProperty(prefix = "rootstock.rag.ingest", name = "poller-enabled", matchIfMissing = true)
public class IngestionJobPoller {

	private static final Logger log = LoggerFactory.getLogger(IngestionJobPoller.class);

	private final IngestionService ingestionService;
	private final Executor executor;
	private final int batchSize;

	public IngestionJobPoller(IngestionService ingestionService,
			@Qualifier(RagConfig.INGESTION_EXECUTOR) Executor executor,
			RagProperties properties) {
		this.ingestionService = ingestionService;
		this.executor = executor;
		this.batchSize = properties.ingest().batchSize();
	}

	@Scheduled(fixedDelayString = "${rootstock.rag.ingest.poll-interval-ms:5000}", initialDelay = 5000)
	public void poll() {
		List<UUID> claimed;
		try {
			claimed = ingestionService.claim(batchSize);
		}
		catch (RuntimeException ex) {
			log.error("Ingestion claim failed", ex);
			return;
		}
		for (UUID jobId : claimed) {
			executor.execute(() -> ingestionService.process(jobId));
		}
	}
}

package com.rootstock.core.rag.ingest;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one job: run the pipeline in its own transaction, and on failure
 * record it (or requeue for retry) in a separate transaction so the error is
 * never rolled back with the work.
 */
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	private final IngestionJobStore store;
	private final IngestionPipeline pipeline;

	public IngestionService(IngestionJobStore store, IngestionPipeline pipeline) {
		this.store = store;
		this.pipeline = pipeline;
	}

	public List<UUID> claim(int limit) {
		return store.claim(limit);
	}

	public void process(UUID jobId) {
		try {
			pipeline.run(jobId);
		}
		catch (RuntimeException ex) {
			log.warn("Ingestion job {} failed: {}", jobId, ex.toString());
			store.markFailedOrRequeue(jobId, ex);
		}
	}
}

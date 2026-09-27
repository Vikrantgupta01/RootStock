package com.rootstock.rag.ingest;

import com.rootstock.rag.RagProperties;
import com.rootstock.rag.document.DocumentStatus;
import com.rootstock.rag.document.DocumentVersion;
import com.rootstock.rag.document.DocumentVersionRepository;
import com.rootstock.rag.vector.BedrockKnowledgeBaseClient;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs one ingestion job: trigger (and wait for) a Bedrock Knowledge Base
 * data-source sync, then flip the {@link DocumentVersion} to {@code INDEXED}.
 *
 * <p>Parsing, chunking, and embedding are owned by Bedrock -- this class only
 * has to make sure the S3 object + metadata sidecar (written by
 * {@link com.rootstock.rag.document.DocumentService}) are picked up by a sync.
 * {@code INGEST} and {@code REINDEX} are handled identically; {@code CLEANUP}
 * just re-syncs so a deletion Bedrock already saw (the S3 object was removed by
 * {@code DocumentService} before this job was queued) takes effect.
 */
@Service
public class IngestionPipeline {

	private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

	private final IngestionJobRepository jobs;
	private final DocumentVersionRepository versions;
	private final BedrockKnowledgeBaseClient kb;
	private final RagProperties properties;

	public IngestionPipeline(IngestionJobRepository jobs, DocumentVersionRepository versions,
			BedrockKnowledgeBaseClient kb, RagProperties properties) {
		this.jobs = jobs;
		this.versions = versions;
		this.kb = kb;
		this.properties = properties;
	}

	public void run(UUID jobId) {
		IngestionJob job = jobs.findById(jobId)
				.orElseThrow(() -> new NoSuchElementException("Ingestion job " + jobId + " vanished"));

		kb.sync(properties.bedrock().knowledgeBaseId(), properties.bedrock().dataSourceId());

		if (job.getKind() != IngestionJobKind.CLEANUP) {
			DocumentVersion version = versions.findById(job.getDocumentVersionId())
					.orElseThrow(() -> new NoSuchElementException("document_version " + job.getDocumentVersionId()));
			version.setStatus(DocumentStatus.INDEXED);
			version.setIndexedAt(Instant.now());
			version.setErrorMessage(null);
			versions.save(version);
			log.info("Indexed document_version {} via Bedrock Knowledge Base sync", version.getId());
		}

		job.setState(IngestionJobState.SUCCEEDED);
		job.setErrorMessage(null);
		jobs.save(job);
	}
}

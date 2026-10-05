package com.rootstock.rag.ingest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactional operations on the {@code ingestion_job} ledger, kept
 * separate from the (longer) pipeline transaction so a failure can be recorded
 * in its own committed transaction.
 */
@Service
public class IngestionJobStore {

	private final IngestionJobRepository jobs;
	private final String workerId;

	public IngestionJobStore(IngestionJobRepository jobs) {
		this.jobs = jobs;
		this.workerId = shortHost() + "/" + Long.toHexString(ProcessHandle.current().pid());
	}

	@Transactional
	public List<UUID> claim(int limit) {
		List<IngestionJob> claimed = jobs.lockQueued(PageRequest.of(0, limit));
		Instant now = Instant.now();
		for (IngestionJob job : claimed) {
			job.setState(IngestionJobState.RUNNING);
			job.incrementAttempts();
			job.setLockedBy(workerId);
			job.setLockedAt(now);
		}
		jobs.saveAll(claimed);
		return claimed.stream().map(IngestionJob::getId).toList();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailedOrRequeue(UUID jobId, Throwable error) {
		jobs.findById(jobId).ifPresent(job -> {
			job.setErrorMessage(truncate(String.valueOf(error), 2000));
			if (job.getAttempts() >= job.getMaxAttempts()) {
				job.setState(IngestionJobState.FAILED);
			}
			else {
				job.setState(IngestionJobState.QUEUED);
				job.setLockedBy(null);
				job.setLockedAt(null);
			}
		});
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static String shortHost() {
		try {
			return java.net.InetAddress.getLocalHost().getHostName();
		}
		catch (Exception e) {
			return "worker";
		}
	}
}

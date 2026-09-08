package com.rootstock.rag.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically finalizes blue/green profile activations once their re-index jobs
 * complete. Shares the ingestion poller's enable flag so integration tests can
 * drive it explicitly.
 */
@Component
@ConditionalOnProperty(prefix = "rootstock.rag.ingest", name = "poller-enabled", matchIfMissing = true)
public class ProfileActivationMonitor {

	private static final Logger log = LoggerFactory.getLogger(ProfileActivationMonitor.class);

	private final RagProfileService profileService;

	public ProfileActivationMonitor(RagProfileService profileService) {
		this.profileService = profileService;
	}

	@Scheduled(fixedDelayString = "${rootstock.rag.ingest.poll-interval-ms:5000}", initialDelay = 7000)
	public void tick() {
		try {
			profileService.finalizePendingActivations();
		}
		catch (RuntimeException ex) {
			log.error("Profile activation finalize failed", ex);
		}
	}
}

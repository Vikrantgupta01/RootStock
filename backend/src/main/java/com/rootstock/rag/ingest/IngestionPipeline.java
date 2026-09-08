package com.rootstock.rag.ingest;

import com.rootstock.rag.document.DocumentStatus;
import com.rootstock.rag.document.DocumentVersion;
import com.rootstock.rag.document.DocumentVersionRepository;
import com.rootstock.rag.profile.RagProfile;
import com.rootstock.rag.profile.RagProfileRepository;
import com.rootstock.rag.profile.RagProfileService;
import com.rootstock.rag.vector.RagChunkMetadata;
import com.rootstock.rag.vector.RagFilters;
import com.rootstock.rag.vector.VectorStoreRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import com.rootstock.rag.blob.BlobStore;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional body of one ingestion job: parse → chunk → embed → write to
 * the vector store, then flip the {@link DocumentVersion} to {@code INDEXED}.
 * Runs inside a single transaction; any exception rolls the whole thing back and
 * the caller records the failure separately.
 */
@Service
public class IngestionPipeline {

	private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

	private final IngestionJobRepository jobs;
	private final DocumentVersionRepository versions;
	private final RagProfileRepository profiles;
	private final RagProfileService profileService;
	private final BlobStore blobStore;
	private final VectorStoreRegistry vectorStores;

	public IngestionPipeline(IngestionJobRepository jobs, DocumentVersionRepository versions,
			RagProfileRepository profiles, RagProfileService profileService, BlobStore blobStore,
			VectorStoreRegistry vectorStores) {
		this.jobs = jobs;
		this.versions = versions;
		this.profiles = profiles;
		this.profileService = profileService;
		this.blobStore = blobStore;
		this.vectorStores = vectorStores;
	}

	@Transactional
	public void run(java.util.UUID jobId) {
		IngestionJob job = jobs.findById(jobId)
				.orElseThrow(() -> new NoSuchElementException("Ingestion job " + jobId + " vanished"));
		switch (job.getKind()) {
			case INGEST, REINDEX -> index(job);
			case CLEANUP -> cleanup(job);
		}
		job.setState(IngestionJobState.SUCCEEDED);
		job.setErrorMessage(null);
	}

	private void index(IngestionJob job) {
		DocumentVersion version = versions.findById(job.getDocumentVersionId())
				.orElseThrow(() -> new NoSuchElementException("document_version " + job.getDocumentVersionId()));

		RagProfile profile = job.getProfileId() != null
				? profiles.findById(job.getProfileId())
						.orElseThrow(() -> new NoSuchElementException("rag_profile " + job.getProfileId()))
				: profileService.activeProfile(version.getTenantId());

		VectorStore store = vectorStores.forModel(profile.getEmbeddingModelId());

		// Idempotency: drop any chunks a previous attempt for this version wrote.
		store.delete(RagFilters.forVersion(version.getTenantId(), version.getId().toString()));

		String text = extractText(readBytes(version.getBlobKey()));
		List<String> pieces = TextChunker.chunk(text, profile.getChunkingStrategy(),
				profile.getChunkSize(), profile.getChunkOverlap());

		List<org.springframework.ai.document.Document> chunks = new ArrayList<>(pieces.size());
		for (int i = 0; i < pieces.size(); i++) {
			Map<String, Object> metadata = new HashMap<>();
			metadata.put(RagChunkMetadata.TENANT_ID, version.getTenantId());
			metadata.put(RagChunkMetadata.DOCUMENT_ID, version.getDocumentId().toString());
			metadata.put(RagChunkMetadata.DOCUMENT_VERSION_ID, version.getId().toString());
			metadata.put(RagChunkMetadata.PROFILE_ID, profile.getId().toString());
			metadata.put(RagChunkMetadata.CHUNK_INDEX, i);
			chunks.add(new org.springframework.ai.document.Document(pieces.get(i), metadata));
		}
		if (!chunks.isEmpty()) {
			store.add(chunks);
		}

		version.setChunkCount(chunks.size());
		version.setStatus(DocumentStatus.INDEXED);
		version.setIndexedAt(Instant.now());
		version.setErrorMessage(null);
		versions.save(version);
		log.info("Indexed document_version {} ({} chunks) under profile {}",
				version.getId(), chunks.size(), profile.getId());
	}

	private void cleanup(IngestionJob job) {
		DocumentVersion version = versions.findById(job.getDocumentVersionId())
				.orElseThrow(() -> new NoSuchElementException("document_version " + job.getDocumentVersionId()));
		RagProfile profile = profileService.activeProfile(version.getTenantId());
		vectorStores.forModel(profile.getEmbeddingModelId())
				.delete(RagFilters.forVersion(version.getTenantId(), version.getId().toString()));
		version.setStatus(DocumentStatus.SUPERSEDED);
		version.setChunkCount(0);
		versions.save(version);
	}

	private byte[] readBytes(String blobKey) {
		try (var in = blobStore.get(blobKey)) {
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read blob " + blobKey, e);
		}
	}

	private String extractText(byte[] bytes) {
		List<org.springframework.ai.document.Document> parsed =
				new TikaDocumentReader(new ByteArrayResource(bytes)).get();
		StringBuilder sb = new StringBuilder();
		for (org.springframework.ai.document.Document d : parsed) {
			if (d.getText() != null) {
				sb.append(d.getText()).append("\n\n");
			}
		}
		return sb.toString();
	}
}

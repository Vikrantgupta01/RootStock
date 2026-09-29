package com.rootstock.rag.document;

import com.rootstock.auth.AuthContext;
import com.rootstock.common.ResourceNotFoundException;
import com.rootstock.common.UnsupportedContentTypeException;
import com.rootstock.rag.RagProperties;
import com.rootstock.rag.access.AccessGroup;
import com.rootstock.rag.blob.BlobStore;
import com.rootstock.rag.document.dto.DocumentDetailResponse;
import com.rootstock.rag.document.dto.DocumentSummaryResponse;
import com.rootstock.rag.document.dto.DocumentVersionResponse;
import com.rootstock.rag.document.dto.UploadResponse;
import com.rootstock.rag.ingest.IngestionJob;
import com.rootstock.rag.ingest.IngestionJobKind;
import com.rootstock.rag.ingest.IngestionJobRepository;
import com.rootstock.rag.tenant.TenantContext;
import com.rootstock.rag.vector.RagChunkMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentService {

	private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
			"application/pdf",
			"text/plain", "text/markdown", "text/html", "text/csv", "application/json",
			"application/msword",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
			"application/vnd.ms-powerpoint",
			"application/vnd.openxmlformats-officedocument.presentationml.presentation");

	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;
	private final IngestionJobRepository jobs;
	private final BlobStore blobStore;
	private final RagProperties properties;
	private final ObjectMapper json;

	public DocumentService(DocumentRepository documents, DocumentVersionRepository versions,
			IngestionJobRepository jobs, BlobStore blobStore, RagProperties properties, ObjectMapper json) {
		this.documents = documents;
		this.versions = versions;
		this.jobs = jobs;
		this.blobStore = blobStore;
		this.properties = properties;
		this.json = json;
	}

	// ---- queries -------------------------------------------------------------

	@Transactional(readOnly = true)
	public Page<DocumentSummaryResponse> list(Pageable pageable) {
		String tenantId = TenantContext.require();
		return documents.findByTenantId(tenantId, pageable).map(this::toSummary);
	}

	@Transactional(readOnly = true)
	public DocumentDetailResponse get(UUID documentId) {
		Document document = requireDocument(documentId);
		return DocumentDetailResponse.of(document, versions.findByDocumentIdOrderByVersionNoDesc(documentId));
	}

	// ---- uploads ----------------------------------------------------------- -

	@Transactional
	@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
	public UploadResponse upload(MultipartFile file, String sourceKeyOverride, String displayNameOverride) {
		String tenantId = TenantContext.require();
		byte[] bytes = read(file);
		String contentType = contentTypeOf(file);
		requireSupported(contentType);

		String sourceKey = sanitizeKey(firstNonBlank(sourceKeyOverride, file.getOriginalFilename(), "upload"));
		String displayName = firstNonBlank(displayNameOverride, file.getOriginalFilename(), sourceKey);

		Document document = documents.findByTenantIdAndSourceKey(tenantId, sourceKey).orElse(null);
		boolean firstUpload = document == null;
		if (firstUpload) {
			document = documents.save(new Document(tenantId, sourceKey, displayName, contentType));
		}
		else {
			document.setDisplayName(displayName);
			document.setContentType(contentType);
		}

		DocumentVersion version = appendVersion(document, bytes, contentType);
		if (firstUpload) {
			document.setActiveVersionId(version.getId());
		}
		return new UploadResponse(document.getId(), DocumentVersionResponse.of(version, document.getActiveVersionId()));
	}

	@Transactional
	@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
	public UploadResponse addVersion(UUID documentId, MultipartFile file) {
		Document document = requireDocument(documentId);
		byte[] bytes = read(file);
		String contentType = contentTypeOf(file);
		requireSupported(contentType);
		document.setContentType(contentType);

		DocumentVersion version = appendVersion(document, bytes, contentType);
		return new UploadResponse(document.getId(), DocumentVersionResponse.of(version, document.getActiveVersionId()));
	}

	private DocumentVersion appendVersion(Document document, byte[] bytes, String contentType) {
		int nextVersionNo = versions.findFirstByDocumentIdOrderByVersionNoDesc(document.getId())
				.map(v -> v.getVersionNo() + 1)
				.orElse(1);
		String hash = sha256Hex(bytes);
		String blobKey = knowledgeBaseObjectKey(document.getTenantId(), document.getId(), nextVersionNo);
		blobStore.put(blobKey, bytes, contentType);

		DocumentVersion version = versions.save(new DocumentVersion(
				document.getId(), document.getTenantId(), nextVersionNo, blobKey, hash, bytes.length));

		// The sidecar tags the version's own UUID (not its versionNo) since that's
		// what query-time retrieval filters against -- only available once saved.
		writeMetadataSidecar(blobKey, document, version.getId());
		jobs.save(new IngestionJob(document.getTenantId(), IngestionJobKind.INGEST,
				version.getId(), null, properties.ingest().maxAttempts()));
		return version;
	}

	// ---- version lifecycle --------------------------------------------------

	@Transactional
	@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
	public DocumentDetailResponse activateVersion(UUID documentId, int versionNo) {
		Document document = requireDocument(documentId);
		DocumentVersion version = requireVersion(document.getId(), versionNo);
		document.setActiveVersionId(version.getId());
		return DocumentDetailResponse.of(document, versions.findByDocumentIdOrderByVersionNoDesc(document.getId()));
	}

	@Transactional
	@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
	public DocumentVersionResponse reindexVersion(UUID documentId, int versionNo) {
		Document document = requireDocument(documentId);
		DocumentVersion version = requireVersion(document.getId(), versionNo);
		byte[] bytes = readBytes(version.getBlobKey());
		blobStore.put(version.getBlobKey(), bytes, document.getContentType());
		writeMetadataSidecar(version.getBlobKey(), document, version.getId());
		jobs.save(new IngestionJob(document.getTenantId(), IngestionJobKind.REINDEX,
				version.getId(), null, properties.ingest().maxAttempts()));
		version.setStatus(DocumentStatus.PENDING);
		version.setErrorMessage(null);
		return DocumentVersionResponse.of(version, document.getActiveVersionId());
	}

	@Transactional
	@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
	public void deleteVersion(UUID documentId, int versionNo) {
		Document document = requireDocument(documentId);
		DocumentVersion version = requireVersion(document.getId(), versionNo);
		purgeKnowledgeBaseCopy(document.getTenantId(), version.getBlobKey());
		if (version.getId().equals(document.getActiveVersionId())) {
			document.setActiveVersionId(null);
		}
		versions.delete(version);
	}

	@Transactional
	@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
	public void deleteDocument(UUID documentId) {
		Document document = requireDocument(documentId);
		for (DocumentVersion version : versions.findByDocumentIdOrderByVersionNoDesc(document.getId())) {
			purgeKnowledgeBaseCopy(document.getTenantId(), version.getBlobKey());
		}
		document.setActiveVersionId(null);
		documents.delete(document); // document_version rows cascade in the DB
	}

	// ---- access control ------------------------------------------------------

	/**
	 * Replaces the document's access grants, then re-syncs every version so the
	 * change actually takes effect: Bedrock filters on the copy of the ACL in the
	 * S3 sidecar, and only notices a rewritten sidecar on its next ingestion job.
	 * Until that job runs, retrieval still enforces the <em>previous</em> grants.
	 *
	 * @param accessGroups the groups allowed to retrieve it; empty restores
	 *                     tenant-wide visibility
	 */
	@Transactional
	@PreAuthorize("hasRole('ADMIN')")
	public DocumentDetailResponse replaceAccessGroups(UUID documentId, Set<AccessGroup> accessGroups) {
		Document document = requireDocument(documentId);
		document.setAccessGroups(new LinkedHashSet<>(accessGroups));

		List<DocumentVersion> all = versions.findByDocumentIdOrderByVersionNoDesc(document.getId());
		for (DocumentVersion version : all) {
			writeMetadataSidecar(version.getBlobKey(), document, version.getId());
			jobs.save(new IngestionJob(document.getTenantId(), IngestionJobKind.REINDEX,
					version.getId(), null, properties.ingest().maxAttempts()));
			version.setStatus(DocumentStatus.PENDING);
			version.setErrorMessage(null);
		}
		return DocumentDetailResponse.of(document, all);
	}

	private void writeMetadataSidecar(String blobKey, Document document, UUID versionId) {
		blobStore.put(blobKey + ".metadata.json", metadataSidecar(document, versionId), "application/json");
	}

	private void purgeKnowledgeBaseCopy(String tenantId, String blobKey) {
		blobStore.delete(blobKey);
		blobStore.delete(blobKey + ".metadata.json");
		// No documentVersionId: the version row (and, via its FK cascade, any job
		// referencing it) is about to be deleted by the caller. CLEANUP jobs don't
		// need it anyway -- IngestionPipeline only uses one to trigger a sync.
		jobs.save(new IngestionJob(tenantId, IngestionJobKind.CLEANUP, null, null,
				properties.ingest().maxAttempts()));
	}

	/**
	 * The single S3 object a document version lives at -- read for downloads,
	 * written/overwritten for (re-)ingestion, deleted on cleanup. Scoped by
	 * tenant/document/version-number rather than content hash, so two versions
	 * (or tenants) that happen to share identical bytes never share a key -- each
	 * version, even an unchanged re-upload, gets its own independent object and
	 * lifecycle.
	 */
	private static String knowledgeBaseObjectKey(String tenantId, UUID documentId, int versionNo) {
		return "rag-kb/" + tenantId + "/" + documentId + "/v" + versionNo;
	}

	/**
	 * The {@code .metadata.json} Bedrock reads alongside the object: the attributes
	 * every retrieval filter is built from.
	 *
	 * <p>Uses Bedrock's fully-typed attribute form rather than the shorter
	 * {@code {"key":"value"}} one because {@code access_groups} is a
	 * {@code STRING_LIST}, which the short form has no way to express. Nothing is
	 * embedded ({@code includeForEmbedding: false}) -- these are identifiers and
	 * permissions, and mixing them into the chunk text would only pollute the
	 * vector with ids nobody will ever ask about.
	 */
	private byte[] metadataSidecar(Document document, UUID versionId) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put(RagChunkMetadata.TENANT_ID, stringAttribute(document.getTenantId()));
		attributes.put(RagChunkMetadata.DOCUMENT_ID, stringAttribute(document.getId().toString()));
		attributes.put(RagChunkMetadata.DOCUMENT_VERSION_ID, stringAttribute(versionId.toString()));
		attributes.put(RagChunkMetadata.ACCESS_GROUPS, stringListAttribute(accessGroupNames(document)));
		return json.writeValueAsBytes(Map.of("metadataAttributes", attributes));
	}

	/**
	 * A document with no explicit grants is visible tenant-wide, which the sidecar
	 * spells as the synthetic {@code __public__} group every caller carries -- a
	 * value the retrieval filter can match, rather than an absent attribute it
	 * would have to special-case.
	 */
	private static List<String> accessGroupNames(Document document) {
		List<String> names = document.getAccessGroups().stream().map(AccessGroup::getName).sorted().toList();
		return names.isEmpty() ? List.of(AuthContext.PUBLIC_GROUP) : names;
	}

	private static Map<String, Object> stringAttribute(String value) {
		return attribute(Map.of("type", "STRING", "stringValue", value));
	}

	private static Map<String, Object> stringListAttribute(List<String> values) {
		return attribute(Map.of("type", "STRING_LIST", "stringListValue", values));
	}

	private static Map<String, Object> attribute(Map<String, Object> value) {
		return Map.of("value", value, "includeForEmbedding", false);
	}

	// ---- download ---------------------------------------------------------- -

	@Transactional(readOnly = true)
	public Download download(UUID documentId, int versionNo) {
		Document document = requireDocument(documentId);
		DocumentVersion version = requireVersion(document.getId(), versionNo);
		Optional<URI> presigned = blobStore.presignGet(version.getBlobKey(), document.getDisplayName());
		return presigned
				.map(Download::redirect)
				.orElseGet(() -> Download.stream(blobStore.get(version.getBlobKey()),
						document.getContentType(), document.getDisplayName(), version.getSizeBytes()));
	}

	/** Either a redirect to a presigned URL, or a stream to proxy through the API. */
	public record Download(URI redirectUri, InputStream body, String contentType, String filename, long sizeBytes) {

		static Download redirect(URI uri) {
			return new Download(uri, null, null, null, 0);
		}

		static Download stream(InputStream body, String contentType, String filename, long sizeBytes) {
			return new Download(null, body, contentType, filename, sizeBytes);
		}

		public boolean isRedirect() {
			return redirectUri != null;
		}
	}

	// ---- helpers ----------------------------------------------------------- -

	private DocumentSummaryResponse toSummary(Document d) {
		List<DocumentVersion> all = versions.findByDocumentIdOrderByVersionNoDesc(d.getId());
		DocumentStatus activeStatus = all.stream()
				.filter(v -> v.getId().equals(d.getActiveVersionId()))
				.map(DocumentVersion::getStatus)
				.findFirst()
				.orElse(null);
		return DocumentSummaryResponse.of(d, all.size(), activeStatus);
	}

	private Document requireDocument(UUID documentId) {
		return documents.findByTenantIdAndId(TenantContext.require(), documentId)
				.orElseThrow(() -> ResourceNotFoundException.of("Document", documentId));
	}

	private DocumentVersion requireVersion(UUID documentId, int versionNo) {
		return versions.findByDocumentIdAndVersionNo(documentId, versionNo)
				.orElseThrow(() -> new ResourceNotFoundException(
						"Version " + versionNo + " of document " + documentId + " was not found."));
	}

	private void requireSupported(String contentType) {
		String base = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase();
		if (!ALLOWED_CONTENT_TYPES.contains(base)) {
			throw new UnsupportedContentTypeException("Unsupported content type: " + contentType
					+ ". Allowed: " + ALLOWED_CONTENT_TYPES);
		}
	}

	private byte[] readBytes(String blobKey) {
		try (var in = blobStore.get(blobKey)) {
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read blob " + blobKey, e);
		}
	}

	private static byte[] read(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("Uploaded file is empty");
		}
		try {
			return file.getBytes();
		}
		catch (IOException e) {
			throw new IllegalStateException("Failed to read upload", e);
		}
	}

	private static String contentTypeOf(MultipartFile file) {
		String ct = file.getContentType();
		return StringUtils.hasText(ct) ? ct : "application/octet-stream";
	}

	private static String sanitizeKey(String raw) {
		String name = Paths.get(raw).getFileName().toString().trim();
		if (name.isEmpty() || name.equals("..") || name.contains("/") || name.contains("\\")) {
			throw new IllegalArgumentException("Invalid source key: " + raw);
		}
		return name.length() > 512 ? name.substring(0, 512) : name;
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (StringUtils.hasText(v)) {
				return v.trim();
			}
		}
		return "upload";
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}

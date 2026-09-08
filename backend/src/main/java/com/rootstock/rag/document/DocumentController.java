package com.rootstock.rag.document;

import com.rootstock.rag.document.dto.DocumentDetailResponse;
import com.rootstock.rag.document.dto.DocumentSummaryResponse;
import com.rootstock.rag.document.dto.DocumentVersionResponse;
import com.rootstock.rag.document.dto.UploadResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * Document upload, versioning and lifecycle. Tenant is taken from the
 * {@code X-Tenant-Id} header (see {@code TenantFilter}).
 */
@RestController
@RequestMapping("/api/rag/documents")
public class DocumentController {

	private final DocumentService service;

	public DocumentController(DocumentService service) {
		this.service = service;
	}

	@GetMapping
	public PagedModel<DocumentSummaryResponse> list(
			@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
		Page<DocumentSummaryResponse> page = service.list(pageable);
		return new PagedModel<>(page);
	}

	@GetMapping("/{id}")
	public DocumentDetailResponse get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(CREATED)
	public UploadResponse upload(
			@RequestParam("file") MultipartFile file,
			@RequestParam(value = "sourceKey", required = false) String sourceKey,
			@RequestParam(value = "displayName", required = false) String displayName) {
		return service.upload(file, sourceKey, displayName);
	}

	@PostMapping(path = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(CREATED)
	public UploadResponse addVersion(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
		return service.addVersion(id, file);
	}

	@PostMapping("/{id}/versions/{versionNo}/activate")
	public DocumentDetailResponse activate(@PathVariable UUID id, @PathVariable int versionNo) {
		return service.activateVersion(id, versionNo);
	}

	@PostMapping("/{id}/versions/{versionNo}/reindex")
	public DocumentVersionResponse reindex(@PathVariable UUID id, @PathVariable int versionNo) {
		return service.reindexVersion(id, versionNo);
	}

	@GetMapping("/{id}/versions/{versionNo}/content")
	public ResponseEntity<InputStreamResource> content(@PathVariable UUID id, @PathVariable int versionNo) {
		DocumentService.Download download = service.download(id, versionNo);
		if (download.isRedirect()) {
			return ResponseEntity.status(302).location(URI.create(download.redirectUri().toString())).build();
		}
		HttpHeaders headers = new HttpHeaders();
		headers.setContentDisposition(ContentDisposition.attachment().filename(download.filename()).build());
		if (download.sizeBytes() > 0) {
			headers.setContentLength(download.sizeBytes());
		}
		MediaType mediaType = download.contentType() != null
				? MediaType.parseMediaType(download.contentType())
				: MediaType.APPLICATION_OCTET_STREAM;
		return ResponseEntity.ok()
				.headers(headers)
				.contentType(mediaType)
				.body(new InputStreamResource(download.body()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(NO_CONTENT)
	public void deleteDocument(@PathVariable UUID id) {
		service.deleteDocument(id);
	}

	@DeleteMapping("/{id}/versions/{versionNo}")
	@ResponseStatus(NO_CONTENT)
	public void deleteVersion(@PathVariable UUID id, @PathVariable int versionNo) {
		service.deleteVersion(id, versionNo);
	}
}

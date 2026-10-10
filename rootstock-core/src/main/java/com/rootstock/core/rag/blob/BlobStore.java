package com.rootstock.core.rag.blob;

import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

/**
 * Content-addressed binary storage for uploaded documents. Keys are opaque
 * strings (this codebase uses {@code rag-kb/<tenantId>/<documentId>/v<versionNo>});
 * {@link #put} is idempotent.
 */
public interface BlobStore {

	void put(String key, byte[] content, String contentType);

	boolean exists(String key);

	InputStream get(String key);

	void delete(String key);

	/**
	 * A short-lived URL a browser can GET directly, when the backend supports it.
	 * Filesystem storage returns {@link Optional#empty()} and callers stream the
	 * bytes through the API instead.
	 *
	 * @param downloadFilename suggested filename for the {@code Content-Disposition}
	 */
	Optional<URI> presignGet(String key, String downloadFilename);
}

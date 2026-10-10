package com.rootstock.core.rag.blob;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Stores blobs as files under a root directory. Keys may contain {@code /}
 * (e.g. {@code rag-kb/tenant/documentId/v1}); path traversal is rejected.
 * Suitable for local development and single-node deployments.
 */
public class FilesystemBlobStore implements BlobStore {

	private final Path root;

	public FilesystemBlobStore(Path root) {
		this.root = root.toAbsolutePath().normalize();
		try {
			Files.createDirectories(this.root);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot create blob directory " + this.root, e);
		}
	}

	private Path resolve(String key) {
		Path path = root.resolve(key).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("Illegal blob key: " + key);
		}
		return path;
	}

	@Override
	public void put(String key, byte[] content, String contentType) {
		Path path = resolve(key);
		try {
			Files.createDirectories(path.getParent());
			Files.write(path, content);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to write blob " + key, e);
		}
	}

	@Override
	public boolean exists(String key) {
		return Files.exists(resolve(key));
	}

	@Override
	public InputStream get(String key) {
		try {
			return Files.newInputStream(resolve(key));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read blob " + key, e);
		}
	}

	@Override
	public void delete(String key) {
		try {
			Files.deleteIfExists(resolve(key));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to delete blob " + key, e);
		}
	}

	@Override
	public Optional<URI> presignGet(String key, String downloadFilename) {
		return Optional.empty();
	}
}

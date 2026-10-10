package com.rootstock.core.rag.blob;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3-backed {@link BlobStore}, configured via {@code rootstock.rag.blob.s3.*}
 * (bucket, region, and an optional custom endpoint).
 */
public class S3BlobStore implements BlobStore {

	private final S3Client s3;
	private final S3Presigner presigner;
	private final String bucket;
	private final Duration presignTtl;

	public S3BlobStore(S3Client s3, S3Presigner presigner, String bucket, Duration presignTtl) {
		this.s3 = s3;
		this.presigner = presigner;
		this.bucket = bucket;
		this.presignTtl = presignTtl;
	}

	@Override
	public void put(String key, byte[] content, String contentType) {
		s3.putObject(
				PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
				RequestBody.fromBytes(content));
	}

	@Override
	public boolean exists(String key) {
		try {
			s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
			return true;
		}
		catch (NoSuchKeyException e) {
			return false;
		}
	}

	@Override
	public InputStream get(String key) {
		return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
	}

	@Override
	public void delete(String key) {
		s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
	}

	@Override
	public Optional<URI> presignGet(String key, String downloadFilename) {
		GetObjectRequest get = GetObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.responseContentDisposition("attachment; filename=\"" + downloadFilename + "\"")
				.build();
		GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
				.signatureDuration(presignTtl)
				.getObjectRequest(get)
				.build();
		try {
			return Optional.of(presigner.presignGetObject(presign).url().toURI());
		}
		catch (URISyntaxException e) {
			throw new IllegalStateException("Invalid presigned URL", e);
		}
	}
}

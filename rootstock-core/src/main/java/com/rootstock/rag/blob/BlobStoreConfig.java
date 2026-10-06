package com.rootstock.rag.blob;

import com.rootstock.rag.RagProperties;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Selects the {@link BlobStore} implementation from
 * {@code rootstock.rag.blob.backend} ({@code filesystem} by default, or
 * {@code s3}). The S3 client honours an optional custom endpoint; credentials
 * come from the default AWS provider chain.
 */
@Configuration(proxyBeanMethods = false)
public class BlobStoreConfig {

	@Bean
	@ConditionalOnProperty(prefix = "rootstock.rag.blob", name = "backend",
			havingValue = "filesystem", matchIfMissing = true)
	BlobStore filesystemBlobStore(RagProperties properties) {
		return new FilesystemBlobStore(Path.of(properties.blob().filesystem().dir()));
	}

	@Bean
	@ConditionalOnProperty(prefix = "rootstock.rag.blob", name = "backend", havingValue = "s3")
	BlobStore s3BlobStore(RagProperties properties) {
		RagProperties.Blob.S3 s3 = properties.blob().s3();
		Region region = Region.of(s3.region());

		S3ClientBuilder clientBuilder = S3Client.builder().region(region);
		S3Presigner.Builder presignerBuilder = S3Presigner.builder().region(region);

		if (StringUtils.hasText(s3.endpoint())) {
			URI endpoint = URI.create(s3.endpoint());
			clientBuilder.endpointOverride(endpoint).forcePathStyle(s3.pathStyleAccess());
			presignerBuilder.endpointOverride(endpoint);
		}
		return new S3BlobStore(clientBuilder.build(), presignerBuilder.build(),
				s3.bucket(), properties.blob().presignTtl());
	}
}

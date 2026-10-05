package com.rootstock.rag.vector;

import com.rootstock.rag.RagProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;

/**
 * Clients for AWS Bedrock's Knowledge Base APIs: {@link BedrockAgentClient} is the
 * control plane (start/poll ingestion jobs), {@link BedrockAgentRuntimeClient} is
 * the data plane (retrieve). Credentials come from the default AWS provider chain,
 * same as the rest of this application's AWS access.
 */
@Configuration(proxyBeanMethods = false)
public class BedrockAgentConfig {

	@Bean
	BedrockAgentClient bedrockAgentClient(RagProperties properties) {
		return BedrockAgentClient.builder().region(Region.of(properties.bedrock().region())).build();
	}

	@Bean
	BedrockAgentRuntimeClient bedrockAgentRuntimeClient(RagProperties properties) {
		return BedrockAgentRuntimeClient.builder().region(Region.of(properties.bedrock().region())).build();
	}
}

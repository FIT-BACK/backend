package com.fitback.backend.external.aitag.config;

import com.fitback.backend.domain.analysis.service.AiTagAnalyzer;
import com.fitback.backend.domain.tag.repository.TagRepository;
import com.fitback.backend.external.aitag.AiTagModelClient;
import com.fitback.backend.external.aitag.AiTagRequestFactory;
import com.fitback.backend.external.aitag.AnalysisImageContentLoader;
import com.fitback.backend.external.aitag.CanonicalAiTagAnalyzer;
import com.fitback.backend.external.aitag.bedrock.BedrockAiTagModelClient;
import com.fitback.backend.external.aitag.openai.OpenAiTagModelClient;
import com.fitback.backend.external.aitag.s3.S3AnalysisImageContentLoader;
import com.fitback.backend.global.config.ImageStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

@Profile("prod")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiTagProperties.class)
public class AiTagProviderConfig {

    @Bean
    @ConditionalOnProperty(
            name = "fitback.ai.tag-analyzer",
            havingValue = "openai"
    )
    public OpenAiTagModelClient openAiTagModelClient(
            AiTagProperties properties,
            ObjectMapper objectMapper
    ) {
        properties.openai().validateForUse();
        return OpenAiTagModelClient.forProduction(
                properties.openai(),
                properties.requestTimeout(),
                objectMapper
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "fitback.ai.tag-analyzer",
            havingValue = "bedrock"
    )
    public BedrockRuntimeClient aiTagBedrockRuntimeClient(
            AiTagProperties properties,
            AwsCredentialsProvider credentialsProvider
    ) {
        AiTagProperties.Bedrock bedrock = properties.bedrock();
        bedrock.validateForUse();
        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                .apiCallTimeout(properties.requestTimeout())
                .apiCallAttemptTimeout(properties.requestTimeout())
                .build();
        return BedrockRuntimeClient.builder()
                .region(Region.of(bedrock.region()))
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(override)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "fitback.ai.tag-analyzer",
            havingValue = "bedrock"
    )
    public BedrockAiTagModelClient bedrockAiTagModelClient(
            AiTagProperties properties,
            ObjectMapper objectMapper,
            BedrockRuntimeClient aiTagBedrockRuntimeClient
    ) {
        return new BedrockAiTagModelClient(
                properties.bedrock(),
                objectMapper,
                aiTagBedrockRuntimeClient
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "fitback.ai.tag-analyzer",
            havingValue = "openai"
    )
    public AiTagAnalyzer openAiTagAnalyzer(
            TagRepository tagRepository,
            S3Client imageS3Client,
            ImageStorageProperties imageStorageProperties,
            OpenAiTagModelClient modelClient
    ) {
        return analyzer(
                tagRepository,
                imageS3Client,
                imageStorageProperties,
                modelClient
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "fitback.ai.tag-analyzer",
            havingValue = "bedrock"
    )
    public AiTagAnalyzer bedrockAiTagAnalyzer(
            TagRepository tagRepository,
            S3Client imageS3Client,
            ImageStorageProperties imageStorageProperties,
            BedrockAiTagModelClient modelClient
    ) {
        return analyzer(
                tagRepository,
                imageS3Client,
                imageStorageProperties,
                modelClient
        );
    }

    private AiTagAnalyzer analyzer(
            TagRepository tagRepository,
            S3Client imageS3Client,
            ImageStorageProperties imageStorageProperties,
            AiTagModelClient modelClient
    ) {
        AnalysisImageContentLoader loader = new S3AnalysisImageContentLoader(
                imageS3Client,
                imageStorageProperties
        );
        return new CanonicalAiTagAnalyzer(
                tagRepository,
                loader,
                new AiTagRequestFactory(),
                modelClient
        );
    }
}

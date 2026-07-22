package com.fitback.backend.global.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ImageStorageConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public S3Client imageS3Client(
            ImageStorageProperties properties,
            AwsCredentialsProvider credentialsProvider
    ) {
        return S3Client.builder()
                .region(Region.of(properties.awsRegion()))
                .credentialsProvider(credentialsProvider)
                .build();
    }
}

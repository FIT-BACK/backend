package com.fitback.backend.global.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ImageStorageConfig {

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
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

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
